mod gen;
mod common;

use bincode;
use libc::{c_uint, size_t};
use rand::distributions::{Standard};
use rand::Rng;
use std::cell::UnsafeCell;

use gen::{ActualRoot};
use common::{Node};

pub type Root = ActualRoot;

pub fn pretty(lol: &dyn Node) -> String {
    let mut s = String::new();
    lol.pp(&mut s);
    s
}

enum Mutation {
    Insert(usize),
    Remove(usize),
    Replace(usize),
    Append(),
}

// libprotobuf-mutator
struct WeightedReservoirSampler {
    total_weight: u64,
    selected: Mutation,
}

const DEFAULT_MUTATE_WEIGHT: u64 = 1000000u64;

impl WeightedReservoirSampler {
    pub const fn new() -> WeightedReservoirSampler {
        WeightedReservoirSampler {
            total_weight: 0,
            selected: Mutation::Append(),
        }
    }

    // TODO(babush): factor this out of the sampler
    fn mutate(&mut self, tokens: &mut Root) -> () {
        
        for token_ith in 0..tokens.children.len() {
            for mutation in vec!(
                Mutation::Insert(token_ith),
                Mutation::Remove(token_ith),
                Mutation::Replace(token_ith),
                ) {
                self.try_select(mutation, DEFAULT_MUTATE_WEIGHT);
            }
        }

        self.try_select(
            Mutation::Append(),
            DEFAULT_MUTATE_WEIGHT,
            );

        match self.selected {
            Mutation::Insert(ith) => {
                tokens.children.insert(
                    ith,
                    rand::thread_rng().sample(Standard),
                    );
            },
            Mutation::Remove(ith) => {
                tokens.children.remove(ith);
            },
            Mutation::Replace(ith)=> {
                tokens.children[ith] = rand::thread_rng().sample(Standard);
            },
            Mutation::Append() => {
                tokens.children.push(rand::thread_rng().sample(Standard));
            },
        }

    }

    fn pick(&mut self, weight: u64) -> bool {
        if weight == 0 {
            return false;
        }
        self.total_weight += weight;
        return weight == self.total_weight || (
            rand::thread_rng().gen_range(1, self.total_weight) <= weight
            )
    }

    fn try_select(&mut self, mutation: Mutation, weight: u64) -> () {
        if self.pick(weight) {
            self.selected = mutation;
        }
    }
}

#[no_mangle]
pub extern fn afl_custom_mutator(data: *const u8,
                     size: size_t, mutated_out: *mut u8, max_size: size_t, _seed: c_uint) -> size_t {
    unsafe {
        let safe_data = std::slice::from_raw_parts(data, size);
        let result: Result<Root, bincode::Error> = bincode::deserialize_from(safe_data);

        let mut c = bincode::config();
        c.limit(max_size as u64);

        match result {
            Ok(mut r) => {
                //println!("Original: {:?}", &r);

                let mut sampler = WeightedReservoirSampler::new();
                sampler.mutate(&mut r);

                //println!("Mutated: {:?}", &r);

                let out_slice: &mut [u8] = std::slice::from_raw_parts_mut(mutated_out, max_size);
                let res = c.serialize_into(out_slice, &r);

                //println!("PP: {}", pretty(&r));

                match res {
                    Ok(_) => c.serialized_size(&r).unwrap() as usize,
                    Err(_) => 0,
                }
            },
            Err(_) => {
                //dbg!("Error deserializing: {}", s);
                let r: Root = rand::thread_rng().sample(Standard);
                let out_slice: &mut [u8] = std::slice::from_raw_parts_mut(mutated_out, max_size);
                let res = c.serialize_into(out_slice, &r);

                //println!("{:?}", c.serialize(&r).unwrap());

                match res {
                    Ok(_) => c.serialized_size(&r).unwrap() as usize,
                    Err(_) => 0,
                }
            },
        }
    }
}

// 1MB limit
const MAX_SIZE: usize = 1024 * 1024;

/*
 * AFL-pp wants a buffer in *new_data and such buffer must be allocated by us.
 * In addition to that, the buffer is never free'd by AFL.
 * So, we need a global string of some sorts because we don't want to waste
 * time allocating memory at every execution.
 */
std::thread_local! {
    static AFL_OUTPUT: UnsafeCell<String> = UnsafeCell::new(String::with_capacity(MAX_SIZE));
}

#[no_mangle]
pub extern fn afl_pre_save_handler(data: *const u8, size: size_t, new_data: *mut *mut u8) -> size_t {
    AFL_OUTPUT.with(|out_cell| {
        unsafe {
            let mut out = &mut *out_cell.get();
            out.clear();

            let safe_data = std::slice::from_raw_parts(data, size);
            let result: Result<Root, bincode::Error> = bincode::deserialize_from(safe_data);

            match result {
                Ok(r) => {
                    r.pp(&mut out);
                    *new_data = (&mut out[..]).as_mut_ptr();
                    out.len()
                },
                Err(s) => {
                    dbg!("Error deserializing: {}", s);
                    0
                }
            }
        }
    })
}

fn get_splicing_points(start: usize, end: usize) -> (usize, usize) {
    let mut a: usize = rand::thread_rng().gen_range(start, end);

    let mut b: usize;
    b = rand::thread_rng().gen_range(start, end);
    if b > a {
        let tmp = b;
        b = a;
        a = tmp;
    }

    (a, b)
}

std::thread_local! {
    static AFL_SPLICE_OUTPUT: UnsafeCell<Vec<u8>> = UnsafeCell::new(Vec::with_capacity(MAX_SIZE));
}

/*
 * TODO: port to AFLpp
 */
#[no_mangle]
pub extern fn afl_custom_splicer(data1: *const u8, size1: size_t,
                                 data2: *const u8, size2: size_t,
                                 new_data: *mut *mut u8) -> size_t {
    /*
     * See afl_pre_save_handler for how memory management works.
     */
    AFL_SPLICE_OUTPUT.with(|splice_cell| {
        unsafe {
            let mut splice = &mut *splice_cell.get();

            let safe_data1 = std::slice::from_raw_parts(data1, size1);
            let result1: Result<Root, bincode::Error> = bincode::deserialize_from(safe_data1);
            let safe_data2 = std::slice::from_raw_parts(data2, size2);
            let result2: Result<Root, bincode::Error> = bincode::deserialize_from(safe_data2);

            match result1 {
                Ok(_) => {
                    match result2 {
                        Ok(_) => {
                            let mut new_root = Root { children: Vec::new() };

                            let r1: Root = rand::thread_rng().sample(Standard);
                            let r2: Root = rand::thread_rng().sample(Standard);

                            let a: usize;
                            let b: usize;
                            let c: usize;
                            let d: usize;

                            if r1.children.len() == 0 {
                                a = 0;
                                b = 0;
                            } else {
                                let (q, r) = get_splicing_points(0, r1.children.len());
                                a = q;
                                b = r;
                            }

                            if r2.children.len() == 0 {
                                c = 0;
                                d = 0;
                            } else {
                                let (q, r) = get_splicing_points(0, r2.children.len());
                                c = q;
                                d = r;
                            }

                            // This is **VERY** slow
                            for i in 0..a {
                                new_root.children.push(r1.children[i].clone());
                            }
                            for i in c..d {
                                new_root.children.push(r2.children[i].clone());
                            }
                            for i in b..r1.children.len() {
                                new_root.children.push(r1.children[i].clone());
                            }

                            //let out_slice: &mut [u8] = std::slice::from_raw_parts_mut(splice, MAX_SIZE);
                            let mut c = bincode::config();
                            c.limit(MAX_SIZE as u64);

                            let res = c.serialize_into(&mut splice, &new_root);

                            println!("\n\n\n\nPP: {}", pretty(&new_root));

                            match res {
                                Ok(_) => {
                                    *new_data = (&mut splice[..]).as_mut_ptr();
                                    c.serialized_size(&new_root).unwrap() as usize
                                },
                                Err(_) => 0,
                            }
                        },
                        Err(_) => {
                            dbg!("ERROR SPLICING");
                            0
                        }
                    }
                },
                Err(s) => {
                    dbg!("ERROR SPLICING");
                    0
                }
            }
        }
    })
}

