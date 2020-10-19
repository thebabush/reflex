//@category Analysis

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.bin.format.elf.ElfHeader;
import ghidra.app.util.bin.format.elf.ElfProgramHeader;
import ghidra.graph.GEdge;
import ghidra.graph.jung.JungDirectedGraph;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.address.GenericAddress;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighConstant;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.pcode.VarnodeAST;
import ghidra.util.xml.XmlAttributes;
import ghidra.util.xml.XmlWriter;

public class Reflex extends GhidraScript {
	
	final static int DECOMPILATION_TIMEOUT = 60 * 5;
	
	public static class XmlAttributesBuilder {
		private XmlAttributes xml;
		
		public XmlAttributesBuilder() {
			xml = new XmlAttributes();
		}
		XmlAttributesBuilder addAttribute(String name, boolean value) {
			xml.addAttribute(name, value);
			return this;
		}
		XmlAttributesBuilder addAttribute(String name, String value) {
			xml.addAttribute(name, value);
			return this;
		}
		XmlAttributesBuilder addAttribute(String name, long value) {
			xml.addAttribute(name, value);
			return this;
		}
		public XmlAttributes build() {
			return xml;
		}
	}
	
	public static class Util {
		
		public static Optional<Long> tryOffsetFromAddress(Address addr) {
			if (addr == null) {
				return Optional.empty();
			}
			return Optional.of(addr.getUnsignedOffset());
		}
		
	}
	
	public static class ReflexResults {
		private long yyAccept;
		private long yyBase;
		private long yyChk;
		private long yyDef;
		private long yyEc;
		private long yyMeta;
		private long yyNxt;
		private int maxState;
		
		public ReflexResults(long accept, long base, long chk, long def, long ec, long meta, long nxt, int maxState) {
			yyAccept = accept;
			yyBase = base;
			yyChk = chk;
			yyDef = def;
			yyEc = ec;
			yyMeta = meta;
			yyNxt = nxt;
			this.maxState = maxState;
		}
		
		public long[] getTables() {
			return new long[] {
				yyAccept,
				yyBase,
				yyChk,
				yyDef,
				yyEc,
				yyMeta,
				yyNxt
			};
		}
	}
	
	protected interface AnalysisListener {
		public void onTablesGuessed(Set<Address> guesses);
	}
	
	enum NodeAttributes {
		ADDRESS,
		PC_ADDRESS,
		FILE_OFFSET,
		COULD_BE_TABLE,
		IS_OP,
		IS_VARNODE,
		IS_INPUT,
		IS_SINK,
		IS_SOURCE,
		CONST_VALUE,
		SIZE,
		OP,
		NAME,
		OP_MNEMONIC,
		OP_SIZE,
		AS_STRING,
		// Edges
		ORDER,
		// neo4j
	}
	
	public static Optional<String> nodeAttrToXmlType(NodeAttributes attr) {
		switch (attr) {
		case PC_ADDRESS:
		case FILE_OFFSET:
			return Optional.of("long");
		case COULD_BE_TABLE:
			return Optional.of("boolean");
		case IS_OP:
		case IS_VARNODE:
		case IS_INPUT:
		case IS_SINK:
		case IS_SOURCE:
			return Optional.of("boolean");
		case CONST_VALUE:
		case SIZE:
		case OP:
			return Optional.of("long");
		case ORDER:
			return Optional.of("int");
		case NAME:
		case OP_MNEMONIC:
			return Optional.of("string");
		case OP_SIZE:
			return Optional.of("long");
		default:
			return Optional.empty();
		}
	}

	public static Optional<String> nodeAttrToXmlStr(NodeAttributes attr, Object value) {
		if (nodeAttrToXmlType(attr).isPresent()) {
			return Optional.of("" + value);
		}
		return Optional.empty();
	}
	
	static class Node {
		Map<NodeAttributes, Object> attributes;
		int id;
		
		public Node(int id) {
			this.id = id;
			attributes = new HashMap<>();
		}
		
		public int getId() {
			return id;
		}
		
		public boolean isVarnode() {
			// HACK
			return id >= 0;
		}
		
		public Map<NodeAttributes, Object> getAttributes() {
			return attributes;
		}
		
		public Object getAttribute(NodeAttributes k) {
			return attributes.get(k);
		}
		
		public void setAttribute(NodeAttributes k, Object v) {
			attributes.put(k, v);
		}
		
		public void setAttributeFromMaybe(NodeAttributes k, Optional<? extends Object> v) {
			if (v.isPresent()) {
				attributes.put(k, v.get());
			}
		}
		
		@Override
		public String toString() {
			var sb = new StringBuilder();
			sb.append("<node id=" + id + ">\n");
			for (var kv : attributes.entrySet()) {
				if (kv.getKey().name().equals("AS_STRING")) {
					continue;
				}
				sb.append(String.format("%s=%s\n", kv.getKey().name(), kv.getValue().toString()));
			}
			sb.append("</node>\n");
			return sb.toString();
		}
	}
	
	static class Edge implements GEdge<Node> {
		Map<NodeAttributes, Object> attributes;
		Node u;
		Node v;
		
		public Edge(Node u, Node v, Node shareAttrs) {
			this.u = u;
			this.v = v;
			attributes = new HashMap<>();
			attributes.putAll(shareAttrs.attributes);
		}
		
		public Edge(Node u, Node v, Edge shareAttrs) {
			this.u = u;
			this.v = v;
			attributes = new HashMap<>();
			attributes.putAll(shareAttrs.attributes);
		}

		public Edge(Node u, Node v, int order) {
			this.u = u;
			this.v = v;
			attributes = new HashMap<>();
			attributes.put(NodeAttributes.ORDER, order);
		}

		public void copyAttrsFrom(Map<NodeAttributes, Object> attrs) {
			attributes.putAll(attrs);
		}
		
		public int getOrder() {
			return (int)attributes.get(NodeAttributes.ORDER);
		}

		@Override
		public Node getStart() {
			return u;
		}

		@Override
		public Node getEnd() {
			return v;
		}
		
		public void setAttribute(NodeAttributes name, Object value) {
			attributes.put(name, value);
		}
		
		public Object getAttribute(NodeAttributes name) {
			return attributes.get(name);
		}
	}
	
	static class Graph extends JungDirectedGraph<Node, Edge> {

		private String escapeString(String s) {
			return s.replace("\"", "\\\"").replace("\n", "\\n");
		}

		public void exportGraphviz(String path) throws FileNotFoundException {
			var writer = new PrintWriter(path);
			writer.write("digraph Out {\n    ordering=in;\n");
			for (var vertex : getVertices()) {
				writer.write(
					String.format(
						"    \"%s\" [ label=\"%s\" ];\n",
						"" + vertex.getId(),
						escapeString("" + vertex)
					)
				);
			}
			List<Edge> edges = new ArrayList<Edge>(getEdges());
			edges.sort((left, right) -> Integer.compare(left.getOrder(), right.getOrder()));
			for (var edge : edges) {
				writer.write(
					String.format(
						"    \"%d\" -> \"%d\" [ label=\"%d\" ];\n",
						edge.getStart().getId(),
						edge.getEnd().getId(),
						edge.getOrder()
					)
				);
			}
			writer.write("}\n");
			writer.close();
		}
		
		public Optional<Graph> simplify(boolean removeCopies, boolean removeIndirects) {
			var other = new Graph();
			
			// Check correctness
			for (var node : getVertices()) {
				if (node.isVarnode() && inDegree(node) > 1) {
					return Optional.empty();
				}
			}
			
			var kept = new HashSet<Node>();
			// Add opnodes and source/sink varnodes
			for (var node : getVertices()) {
				// Remove a varnode if it's alone or if it is intermediary
				if (node.isVarnode() && !(inDegree(node) > 0 ^ outDegree(node) > 0)) {
					continue;
				}
				other.addVertex(node);
				kept.add(node);
			}
			
			// Rewrite edges
			for (var edge : getEdges()) {
				var u = edge.getStart();
				var v = edge.getEnd();
				if (u.isVarnode()) {
					// Take care of source varnodes
					if (kept.contains(u)) {
						other.addEdge(edge);
					}
				} else {
					// v is a varnode by construction
					var nextEdges = getOutEdges(v);
					if (nextEdges.size() == 0) {
						// v is a terminator and is a varnode
						other.addEdge(edge);
					} else {
						// Take care of opnodes (we just need the out edges)
						for (var nextEdge : nextEdges) {
							var nextV = nextEdge.getEnd();
							nextEdge.copyAttrsFrom(v.getAttributes());
							other.addEdge(new Edge(u, nextV, nextEdge));
						}
					}
				}
			}
			
			// Remove copies
			if (removeCopies) {
				var copies = new HashSet<Node>();
				var newEdges = new ArrayList<Edge>();
				for (var node : other.getVertices()) {
					if (!node.isVarnode() && PcodeOp.COPY == (Integer)node.getAttribute(NodeAttributes.OP)) {
						copies.add(node);
					}
				}
				for (var edge : other.getEdges()) {
					var u = edge.getStart();
					var v = edge.getEnd();
					if (copies.contains(v)) {
						for (var nextEdge : other.getOutEdges(v)) {
							newEdges.add(new Edge(u, nextEdge.getEnd(), nextEdge));
						}
					}
				}
				for (var e : newEdges) {
					other.addEdge(e);
				}
				other.removeVertices(copies);
			}
			
			// Remove indirects
			if (removeIndirects) {
				var indirects = 
					other
					.getVertices()
					.stream()
					.filter(n -> !n.isVarnode() && PcodeOp.INDIRECT == (Integer)n.getAttribute(NodeAttributes.OP))
					.collect(Collectors.toList());
				other.removeVertices(indirects);
			}
			
			return Optional.of(other);
		}

	}
	
	static class DefaultMap<T, R> {
		private java.util.function.Function<T, R> creator;
		private HashMap<T, R> map;
		
		public DefaultMap(java.util.function.Function<T, R> creator) {
			this.creator = creator;
			map = new HashMap<>();
		}
		
		public R getOrCreate(T id) {
			var value = map.get(id);
			if (value == null) {
				var newVal = creator.apply(id);
				map.put(id, newVal);
				return newVal;
			}
			return value;
		}
	}

	protected static class ReflexAnalysis {
		
		private AnalysisListener listener;

		private Consumer<String> log;
		private FlatProgramAPI flatApi;
		private DecompInterface decomp;
		private AddressSpace addressSpace;

		public ReflexAnalysis(Program currentProgram, Consumer<String> log) {
			this(currentProgram, log, new AnalysisListener() {
				@Override
				public void onTablesGuessed(Set<Address> guesses) {
				}
			});
		}

		public ReflexAnalysis(Program currentProgram, Consumer<String> log, AnalysisListener listener) {
			this.listener = listener;
			this.log = log;
			
			flatApi = new FlatProgramAPI(currentProgram);

			decomp = new DecompInterface();
			decomp.openProgram(currentProgram);
//			decomp.setSimplificationStyle("normalize");
			
			addressSpace = flatApi.getAddressFactory().getDefaultAddressSpace();
		}
		
		private boolean isAddressValid(Address addr) {
			var block = flatApi.getMemoryBlock(addr);
			if (block != null) {
				if (block.isInitialized() && block.isLoaded() && block.isRead() && !block.isWrite()) {
					return true;
				}
			}
			return false;
		}
		
		private Optional<HighConstant> tryVarnodeToHighConstant(Varnode node) {
			// Skip raw varnodes
			if (!(node instanceof VarnodeAST)) {
				return Optional.empty();
			}
			// Some constant VarnodeAST's are not HighConstant, so use instanceof
			var hiVar = ((VarnodeAST)node).getHigh();
			if (!(hiVar instanceof HighConstant)) {
				return Optional.empty();
			}
			return Optional.of((HighConstant)hiVar);
		}
		
		private Optional<Address> tryVarnodeToConstAddress(Varnode node) {
			// Try to get a HighConstant
			var maybeK = tryVarnodeToHighConstant(node);
			if (maybeK.isEmpty()) {
				return Optional.empty();
			}
			var k = maybeK.get();
			// Map it to an address
			var addrRaw = k.getScalar().getUnsignedValue();
			if (!addressSpace.isValidRange(addrRaw, k.getSize())) {
				return Optional.empty();
			}
			var addr = addressSpace.getAddress(addrRaw);
			if (isAddressValid(addr)) {
				return Optional.of(addr);
			}
			return Optional.empty();
		}
		
		private Optional<HighFunction> decompile(Function f) {
			var decompileResults = decomp.decompileFunction(f, DECOMPILATION_TIMEOUT, null);
			if (decompileResults == null || !decompileResults.decompileCompleted()) {
				return Optional.empty();
			}
			return Optional.ofNullable(decompileResults.getHighFunction());
		}
		
		public Set<Address> findTableGuesses(HighFunction f) {
			var out = new HashSet<Address>();
			var it = f.getPcodeOps();
			while (it.hasNext()) {
				var op = it.next();
				for (var input : op.getInputs()) {
					var address = tryVarnodeToConstAddress(input);
					if (address.isPresent()) {
						out.add(address.get());
					}
				}
			}
			return out;
		}
		
		public void doit(Function yylex) {
			// Get the decompiled function
			var maybeHi = decompile(yylex);
			if (maybeHi.isEmpty()) {
				log.accept("Couldn't decompile the given function :/");
				return;
			}
			var hf = maybeHi.get();
			
			// Guess the tables
			var tables = findTableGuesses(hf);
			if (tables.size() < 7) {
				log.accept("Less than 7 table guesses :/");
				return;
			}
			listener.onTablesGuessed(tables);
			
			var graph = buildGraph(hf);
			try {
				graph.exportGraphviz("/tmp/out.dot");
			} catch (FileNotFoundException e) {
				log.accept("Couldn't open graphviz output file...");
			}
			
			var maybeSimple = graph.simplify(true, true);
			if (maybeSimple.isPresent()) {
				var simple = maybeSimple.get();
				try {
					simple.exportGraphviz("/tmp/simple.dot");
				} catch (FileNotFoundException e) {
					log.accept("Couldn't open graphviz output file...");
				}
				try {
					var xml = new XmlWriter(new File("/tmp/out.xml"), null);
					xml.startElement("graphml");
					BiConsumer<NodeAttributes, String> mkAttr = (attr, type) -> {
						String name = attr.name();
						xml.startElement(
							"key",
							new XmlAttributesBuilder()
								.addAttribute("id", name)
								.addAttribute("attr.name", name.toLowerCase())
								.addAttribute("attr.type", type)
								.build()
						);
//						xml.writeElement("default", null, "");
						xml.endElement("key");
					};
					for (var attr : NodeAttributes.values()) {
						var maybeTy = nodeAttrToXmlType(attr);
						if (maybeTy.isPresent()) {
							mkAttr.accept(attr, maybeTy.get());
						}
					}
					xml.startElement(
						"graph",
						new XmlAttributesBuilder()
							.addAttribute("id", "G")
							.addAttribute("edgedefault", "directed")
							.build()
					);

					//  boolean, int, long, float, double, or string
					for (var node : simple.getVertices()) {
						xml.startElement(
							"node",
							new XmlAttributesBuilder()
								.addAttribute("id", "" + node.getId())
								.addAttribute("labels", ":Ghidra")
								.build()
						);
						for (var attr : node.attributes.entrySet()) {
							var maybeValue = nodeAttrToXmlStr(attr.getKey(), attr.getValue());
							if (maybeValue.isEmpty()) {
								continue;
							}
							xml.writeElement(
								"data",
								new XmlAttributesBuilder().addAttribute("key", attr.getKey().name()).build(),
								maybeValue.get()
							);
						}
						xml.endElement("node");
					}
					for (var edge : simple.getEdges()) {
						xml.startElement(
							"edge",
							new XmlAttributesBuilder()
								.addAttribute("source", "" + edge.getStart().getId())
								.addAttribute("target", "" + edge.getEnd().getId())
								.build()
						);
						for (var attr : edge.attributes.entrySet()) {
							var maybeValue = nodeAttrToXmlStr(attr.getKey(), attr.getValue());
							if (maybeValue.isEmpty()) {
								continue;
							}
							xml.writeElement(
								"data",
								new XmlAttributesBuilder().addAttribute("key", attr.getKey().name()).build(),
								maybeValue.get()
							);
						}
						xml.endElement("edge");
					}
					xml.endElement("graph");
					xml.endElement("graphml");
					xml.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		private void extractInfo(Node node, VarnodeAST ast, boolean isInput) {
			var addr = tryVarnodeToConstAddress(ast);
			
			node.setAttribute(NodeAttributes.IS_VARNODE, true);
			node.setAttribute(NodeAttributes.IS_INPUT, isInput);
			node.setAttribute(NodeAttributes.AS_STRING, ast.toString());
			node.setAttribute(NodeAttributes.NAME, ast.getAddress().toString(true));
			node.setAttribute(NodeAttributes.SIZE, ast.getSize());
			node.setAttributeFromMaybe(NodeAttributes.ADDRESS, addr);
			node.setAttributeFromMaybe(NodeAttributes.PC_ADDRESS, Util.tryOffsetFromAddress(ast.getPCAddress()));
			
			if (ast.isConstant()) {
				node.setAttribute(NodeAttributes.CONST_VALUE, ast.getOffset());
			}
			
			var fileOffset = Optional.empty();
			if (addr.isPresent()) {
				var block = flatApi.getMemoryBlock(addr.get());
				var infos = block.getSourceInfos();
				if (infos != null) {
					for (var info : infos) {
						if (!info.contains(addr.get())) {
							continue;
						}
						fileOffset = Optional.of(info.getFileBytesOffset(addr.get()));
					}
				}
			}
			node.setAttributeFromMaybe(NodeAttributes.FILE_OFFSET, fileOffset);
			
			// Guess tables
			if (isInput) {
				node.setAttribute(NodeAttributes.COULD_BE_TABLE, addr.isPresent());
			}
		}
		
		private void extractInfo(Node node, PcodeOpAST ast) {
			node.setAttribute(NodeAttributes.IS_OP, true);
			node.setAttribute(NodeAttributes.AS_STRING, ast.toString());
			node.setAttribute(NodeAttributes.OP, ast.getOpcode());
			node.setAttribute(NodeAttributes.OP_MNEMONIC, ast.getMnemonic());
			if (ast.getOutput() != null) {
				node.setAttribute(NodeAttributes.OP_SIZE, ast.getOutput().getSize());
			}
		}
		
		private Graph buildGraph(final HighFunction hf) {
			var graph = new Graph();
			
			var varnodeCache = new DefaultMap<Integer, Node>(id -> {
				var node = new Node(id);
				graph.addVertex(node);
				return node;
			});
			// HACK: we are going to use negative id numbers for operations...
			int opnodeId = 0;

			var it = hf.getPcodeOps();
			while (it.hasNext()) {
				var op = it.next();
				var opcode = op.getOpcode();

				var opnode = varnodeCache.getOrCreate(--opnodeId);
				extractInfo(opnode, op);

				// Iteration is faster than functional API :(
				for (int i = 0; i < op.getNumInputs(); ++i) {
					// Filter uninteresting nodes
					if (i == 0 && (opcode == PcodeOp.STORE || opcode == PcodeOp.LOAD)) {
						// STOREs/LOADs have an address space parameter we don't care about
						continue;
					} else if (i == 1 && opcode == PcodeOp.INDIRECT) {
						// INDIRECTs have an opcode int as second input to describe the type of the operation
						// they depends upon. Not interesting.
						continue;
					}

					// Convert the input to a properVarnode
					var rawVarnode = op.getInput(i);
					if (rawVarnode == null || !(rawVarnode instanceof VarnodeAST)) {
						continue;
					}
					var varnode = (VarnodeAST)rawVarnode;
					var graphVn = varnodeCache.getOrCreate(varnode.getUniqueId());
					extractInfo(graphVn, varnode, true);
					
					// Add input edge
					graph.addEdge(new Edge(graphVn, opnode, i));
				}
				
				// Take care of the output
				var rawVarnode = op.getOutput();
				if (rawVarnode != null && rawVarnode instanceof VarnodeAST) {
					var varnode = (VarnodeAST)rawVarnode;
					var graphVn = varnodeCache.getOrCreate(varnode.getUniqueId());
					extractInfo(graphVn, varnode, false);
					
					// Add output edge
					graph.addEdge(new Edge(opnode, graphVn, 0));
				}
			}

			return graph;
		}
	}
	
	public Optional<Function> getFunk(String name) {
		var funkList = getGlobalFunctions(name);
		if (funkList == null || funkList.size() == 0) {
			return Optional.empty();
		}
		for (var funk : funkList) {
			if (!funk.isThunk()) {
				return Optional.of(funk);
			}
		}
		return Optional.empty();
	}
	
	public Optional<Function> getYylex() {
		return getFunk("yylex");
	}
	
	@Override
	public void run() throws Exception {
		var reflex = new ReflexAnalysis(currentProgram, msg -> println(msg));

		var yylexAddrStr = System.getenv("REFLEX_YYLEX");
		if (yylexAddrStr != null) {
			Long newAddr;
			if (yylexAddrStr.startsWith("0x")) {
				yylexAddrStr = yylexAddrStr.substring(2);
				newAddr = Long.parseLong(yylexAddrStr, 16);
			} else {
				newAddr = Long.parseLong(yylexAddrStr, 10);
			}
			var flatApi = new FlatProgramAPI(currentProgram);
			currentAddress = flatApi.toAddr(newAddr);
		}

		var funk = getFunctionContaining(currentAddress);
		if (funk == null) {
			println("This script must be run inside a function!");
			return;
		}
		
		reflex.doit(funk);
	}

}
