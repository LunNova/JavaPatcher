package me.nallar.javapatcher.patcher;

import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import me.nallar.javapatcher.PatcherLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.*;
import java.util.regex.*;

enum DomUtil {
	;

	private final static Pattern stringMatcher = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

	/**
	 * Converts a NodeList of Nodes to a java List containing only the Elements from that list
	 *
	 * @param nodeList NodeList to convert
	 * @return The converted List
	 */
	public static List<Element> elementList(NodeList nodeList) {
		List<Node> nodes = nodeList(nodeList);
		ArrayList<Element> elements = new ArrayList<Element>(nodes.size());
		for (Node node : nodes) {
			if (node instanceof Element) {
				elements.add((Element) node);
			}
		}
		elements.trimToSize();
		return elements;
	}

	/**
	 * Converts a NodeList to a java List
	 *
	 * @param nodeList NodeList to convert
	 * @return The converted List
	 */
	public static List<Node> nodeList(NodeList nodeList) {
		return new NodeListWhichIsActuallyAList(nodeList);
	}

	/**
	 * Creates a map from the attributes of a Node
	 *
	 * @param node Node to get attributes from
	 * @return Map of attributes to values
	 */
	public static Map<String, String> getAttributes(Node node) {
		NamedNodeMap attributeMap = node.getAttributes();
		HashMap<String, String> attributes = new HashMap<String, String>(attributeMap.getLength());
		for (int i = 0; i < attributeMap.getLength(); i++) {
			Node attr = attributeMap.item(i);
			if (attr instanceof Attr) {
				attributes.put(((Attr) attr).getName(), ((Attr) attr).getValue());
			}
		}
		return attributes;
	}

	/**
	 * Reads an XML document from the given InputStream and closes the InputStream
	 *
	 * @param document String containing XML
	 * @return XML Document
	 * @throws java.io.IOException
	 * @throws org.xml.sax.SAXException
	 */
	public static Document readDocumentFromString(String document) throws IOException, SAXException {
		if (document == null) {
			throw new NullPointerException("configInputStream");
		}
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		try {
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			return docBuilder.parse(new InputSource(new StringReader(document)));
		} catch (ParserConfigurationException e) {
			//This exception is thrown, and no shorthand way of getting a DocumentBuilder without it.
			//Should not be thrown, as we do not do anything to the DocumentBuilderFactory.
			PatcherLog.severe("Java was bad, this shouldn't happen. DocBuilder instantiation via default docBuilderFactory failed", e);
		}
		return null;
	}

	public static String makePatchXmlFromJson(String json) {
		Matcher m = stringMatcher.matcher(json);
		StringBuffer sb = new StringBuffer(json.length());
		while (m.find()) {
			m.appendReplacement(sb, escapeStringForJson(m.group(1)).replace("\\", "\\\\").replace("$", "\\$"));
		}
		m.appendTail(sb);
		json = sb.toString();
		try {
			return toString(new JSONObject(json), null);
		} catch (JSONException e) {
			throw new IllegalArgumentException("Invalid json: " + json, e);
		}
	}

	static String toString(Object object, String tagName) throws JSONException {
		StringBuilder sb = new StringBuilder();
		int i;
		JSONArray ja;
		JSONObject jo;
		String key;
		Iterator<String> keys;
		int length;
		String string;
		Object value;
		if (object instanceof JSONObject) {
			jo = (JSONObject) object;
			if (tagName != null) {
				sb.append('<');
				sb.append(tagName);
				keys = jo.keys();
				while (keys.hasNext()) {
					key = keys.next();
					value = jo.opt(key);
					if (!(value instanceof JSONObject)) {
						if ("".equals(value)) {
							sb.append(' ').append(key).append("=\"true\"");
						} else {
							sb.append(' ').append(key).append("=\"").append(escapeStringForXml(value.toString())).append('"');
						}
					}
				}
				sb.append('>');
			}
			keys = jo.keys();
			while (keys.hasNext()) {
				key = keys.next();
				value = jo.opt(key);
				if (value == null) {
					value = "";
				}
				if ("target".equals(key)) {
					if (value instanceof JSONArray) {
						ja = (JSONArray) value;
						length = ja.length();
						for (i = 0; i < length; i += 1) {
							if (i > 0) {
								sb.append('\n');
							}
							sb.append(escapeStringForXml(ja.get(i).toString()));
						}
					} else {
						sb.append(escapeStringForXml(value.toString()));
					}
				} else if (value instanceof JSONArray) {
					ja = (JSONArray) value;
					length = ja.length();
					for (i = 0; i < length; i += 1) {
						value = ja.get(i);
						if (value instanceof JSONArray) {
							sb.append('<');
							sb.append(key);
							sb.append('>');
							sb.append(toString(value, null));
							sb.append("</");
							sb.append(key);
							sb.append('>');
						} else {
							sb.append(toString(value, key));
						}
					}
				} else if (value instanceof JSONObject) {
					sb.append(toString(value, key));
				}
			}
			if (tagName != null) {
				sb.append("</");
				sb.append(tagName);
				sb.append('>');
			}
			return sb.toString();
		} else {
			if (object.getClass().isArray()) {
				object = new JSONArray(object);
			}
			if (object instanceof JSONArray) {
				ja = (JSONArray) object;
				length = ja.length();
				for (i = 0; i < length; i += 1) {
					sb.append(toString(ja.opt(i), tagName == null ? "array" : tagName));
				}
				return sb.toString();
			} else {
				string = escapeStringForXml(object.toString());
				return (tagName == null) ? '"' + string + '"' :
						(string.length() == 0) ? '<' + tagName + "/>" :
								'<' + tagName + '>' + string + "</" + tagName + '>';
			}
		}
	}

	static String escapeStringForXml(String string) {
		StringBuilder sb = new StringBuilder(string.length());
		for (int i = 0, length = string.length(); i < length; i++) {
			char c = string.charAt(i);
			switch (c) {
				case '&':
					sb.append("&amp;");
					break;
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				case '\'':
					sb.append("&apos;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	static String escapeStringForJson(String string) {
		if (string == null || string.length() == 0) {
			return "\"\"";
		}

		char c;
		int i;
		int len = string.length();
		StringBuilder sb = new StringBuilder(len + 4);
		String t;

		sb.append('"');
		for (i = 0; i < len; i += 1) {
			c = string.charAt(i);
			switch (c) {
				case '\\':
				case '"':
				case '/':
					sb.append('\\');
					sb.append(c);
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\t':
					sb.append("\\t");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\r':
					sb.append("\\r");
					break;
				default:
					if (c < ' ') {
						t = "000" + Integer.toHexString(c);
						sb.append("\\u").append(t.substring(t.length() - 4));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	public static String readInputStreamToString(InputStream inputStream) {
		try {
			InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
			return CharStreams.toString(inputStreamReader);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (inputStream != null) {
				try {
					Closeables.close(inputStream, true);
				} catch (IOException impossible) {
					throw new AssertionError(impossible);
				}
			}
		}
	}

	private static class NodeListWhichIsActuallyAList extends AbstractList<Node> implements List<Node> {
		private final NodeList nodeList;

		NodeListWhichIsActuallyAList(NodeList nodeList) {
			this.nodeList = nodeList;
		}

		@Override
		public Node get(int index) {
			return nodeList.item(index);
		}

		@Override
		public int size() {
			return nodeList.getLength();
		}
	}
}
