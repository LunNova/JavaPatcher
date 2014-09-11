package me.nallar.javapatcher.patcher;

import me.nallar.javapatcher.PatcherLog;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.*;

enum DomUtil {
	;

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
	 * @param configInputStream InputStream to read the document from
	 * @return XML Document
	 * @throws IOException
	 * @throws SAXException
	 */
	public static Document readDocumentFromInputStream(InputStream configInputStream) throws IOException, SAXException {
		if (configInputStream == null) {
			throw new NullPointerException("configInputStream");
		}
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		try {
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			return docBuilder.parse(configInputStream);
		} catch (ParserConfigurationException e) {
			//This exception is thrown, and no shorthand way of getting a DocumentBuilder without it.
			//Should not be thrown, as we do not do anything to the DocumentBuilderFactory.
			PatcherLog.severe("Java was bad, this shouldn't happen. DocBuilder instantiation via default docBuilderFactory failed", e);
		} finally {
			configInputStream.close();
		}
		return null;
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
