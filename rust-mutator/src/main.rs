use std::fs::File;

use mutator::{Root, pretty};

fn main() {
    let args : Vec<String> = std::env::args().collect();
    if args.len() != 2 {
        println!("Usage: {} /path/to/input.bin", &args[0]);
        return;
    }

    let filename = &args[1];
    //println!("Dumping \"{}\"...", filename);

    let f = File::open(filename).unwrap();
    let root: Result<Root, bincode::Error> = bincode::deserialize_from(f);

    match root {
        Ok(root) => {
            //println!("{:?}", root);
            println!("{}", pretty(&root));
        },
        Err(e) => println!("{}", e.to_string()),
    };
}
