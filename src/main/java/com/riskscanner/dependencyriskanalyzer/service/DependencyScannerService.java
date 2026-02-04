package com.riskscanner.dependencyriskanalyzer.service;

import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

import javax.xml.parsers.*;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a local filesystem path for dependency declarations.
 *
 * <p>Supported inputs:
 * <ul>
 *   <li>A project folder that contains a recognized build file</li>
 *   <li>A direct path to a build file</li>
 * </ul>
 *
 * <p>Supported build tools:
 * <ul>
 *   <li>Maven: {@code pom.xml}</li>
 *   <li>Gradle: {@code build.gradle} / {@code build.gradle.kts}</li>
 * </ul>
 */
@Service
public class DependencyScannerService {

	// Method to scan pom.xml or build.gradle file for dependencies
	/**
	 * Legacy scan method that returns string coordinates in the form {@code groupId:artifactId:version}.
	 * Prefer {@link #scanProject(String)} for richer output.
	 */
	public List<String> scanDependencies(String filePath) throws Exception {
		List<DependencyCoordinate> coordinates = scanProject(filePath);
		List<String> dependencies = new ArrayList<>();
		for (DependencyCoordinate coordinate : coordinates) {
			dependencies.add(coordinate.groupId() + ":" + coordinate.artifactId() + ":" + coordinate.version());
		}
		return dependencies;
	}

	/**
	 * Scans a project folder (or build file) and returns structured dependency coordinates.
	 *
	 * <p>If a directory is provided, the scanner selects the first supported build file in this order:
	 * {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts}.
	 */
	public List<DependencyCoordinate> scanProject(String projectPath) throws Exception {
		Path inputPath = Paths.get(projectPath);
		Path buildFilePath;
		if (Files.isDirectory(inputPath)) {
			Path pom = inputPath.resolve("pom.xml");
			Path gradle = inputPath.resolve("build.gradle");
			Path gradleKts = inputPath.resolve("build.gradle.kts");
			if (Files.exists(pom)) {
				buildFilePath = pom;
			} else if (Files.exists(gradle)) {
				buildFilePath = gradle;
			} else if (Files.exists(gradleKts)) {
				buildFilePath = gradleKts;
			} else {
				throw new Exception("No pom.xml, build.gradle, or build.gradle.kts found in selected folder.");
			}
		} else {
			buildFilePath = inputPath;
		}

		String fileName = buildFilePath.getFileName().toString();
		if (fileName.equalsIgnoreCase("pom.xml")) {
			return scanMavenDependencies(buildFilePath);
		}
		if (fileName.equalsIgnoreCase("build.gradle") || fileName.equalsIgnoreCase("build.gradle.kts")) {
			return scanGradleDependencies(buildFilePath);
		}
		throw new Exception("Unsupported file format. Only pom.xml, build.gradle, or build.gradle.kts files are supported.");
	}

	// Method to scan Maven pom.xml file for dependencies
	public List<DependencyCoordinate> scanMavenDependencies(Path pomXmlPath) throws Exception {
		List<DependencyCoordinate> dependencies = new ArrayList<>();

		// Read the pom.xml file
		File file = pomXmlPath.toFile();

		// Parse the XML file
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(file);
		doc.getDocumentElement().normalize();

		Map<String, String> properties = readMavenProperties(doc);
		Map<String, String> managedVersions = readMavenDependencyManagement(doc, properties);
		String parentVersion = readParentVersion(doc);

		XPath xPath = XPathFactory.newInstance().newXPath();

		NodeList directDependencies = (NodeList) xPath.evaluate(
				"/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']",
				doc,
				XPathConstants.NODESET);

		addMavenDependenciesFromNodeList(directDependencies, dependencies, properties, managedVersions, parentVersion);

		return dependencies;
	}

	private void addMavenDependenciesFromNodeList(NodeList nodeList, List<DependencyCoordinate> dependencies, Map<String, String> properties, Map<String, String> managedVersions, String parentVersion) {
		if (nodeList == null) {
			return;
		}

		// Iterate through the node list and extract data
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) node;

				// Extract groupId, artifactId, and version
				String groupId = getTagValue("groupId", element);
				String artifactId = getTagValue("artifactId", element);
				String version = getTagValue("version", element);

				// Resolve version using hierarchy: explicit > dependencyManagement > parent version
				if (version == null || version.trim().isEmpty()) {
					String key = (groupId == null ? "" : groupId) + ":" + artifactId;
					version = managedVersions.get(key);
				}
				if (version == null || version.trim().isEmpty()) {
					version = parentVersion;
				}
				version = resolveMavenVersion(version, properties);

				dependencies.add(new DependencyCoordinate(
						groupId == null ? "Unknown" : groupId,
						artifactId == null ? "Unknown" : artifactId,
						version == null ? "Unknown" : version,
						"maven"));
			}
		}
	}

	// Helper method to get the value of a tag
	private String getTagValue(String tag, Element element) {
		NodeList nodeList = element.getElementsByTagNameNS("*", tag);
		if (nodeList.getLength() == 0) {
			nodeList = element.getElementsByTagName(tag);
		}
		if (nodeList.getLength() == 0) {
			return null;
		}
		Node node = nodeList.item(0);
		return node.getTextContent();
	}

	private Map<String, String> readMavenDependencyManagement(Document doc, Map<String, String> properties) {
		Map<String, String> managedVersions = new HashMap<>();
		NodeList dmNodes = doc.getElementsByTagNameNS("*", "dependencyManagement");
		if (dmNodes.getLength() == 0) {
			dmNodes = doc.getElementsByTagName("dependencyManagement");
		}
		if (dmNodes.getLength() == 0) {
			return managedVersions;
		}
		Node dmNode = dmNodes.item(0);
		NodeList deps = dmNode.getChildNodes();
		for (int i = 0; i < deps.getLength(); i++) {
			Node dep = deps.item(i);
			if (dep.getNodeType() != Node.ELEMENT_NODE || !"dependencies".equals(dep.getLocalName())) {
				continue;
			}
			NodeList depList = dep.getChildNodes();
			for (int j = 0; j < depList.getLength(); j++) {
				Node depItem = depList.item(j);
				if (depItem.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(depItem.getLocalName())) {
					continue;
				}
				Element depEl = (Element) depItem;
				String groupId = getTagValue("groupId", depEl);
				String artifactId = getTagValue("artifactId", depEl);
				String version = getTagValue("version", depEl);
				if (groupId != null && artifactId != null && version != null) {
					String key = groupId + ":" + artifactId;
					managedVersions.put(key, resolveMavenVersion(version, properties));
				}
			}
		}
		return managedVersions;
	}

	private String readParentVersion(Document doc) {
		NodeList parentNodes = doc.getElementsByTagNameNS("*", "parent");
		if (parentNodes.getLength() == 0) {
			parentNodes = doc.getElementsByTagName("parent");
		}
		if (parentNodes.getLength() == 0) {
			return null;
		}
		Node parentNode = parentNodes.item(0);
		if (parentNode.getNodeType() != Node.ELEMENT_NODE) {
			return null;
		}
		Element parentEl = (Element) parentNode;
		return getTagValue("version", parentEl);
	}

	private Map<String, String> readMavenProperties(Document doc) {
		Map<String, String> properties = new HashMap<>();
		NodeList propertiesNodes = doc.getElementsByTagNameNS("*", "properties");
		if (propertiesNodes.getLength() == 0) {
			propertiesNodes = doc.getElementsByTagName("properties");
		}
		if (propertiesNodes.getLength() == 0) {
			return properties;
		}
		Node propertiesNode = propertiesNodes.item(0);
		NodeList children = propertiesNode.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String key = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
			properties.put(key, child.getTextContent());
		}
		return properties;
	}

	private String resolveMavenVersion(String version, Map<String, String> properties) {
		if (version == null) {
			return null;
		}
		String trimmed = version.trim();
		Pattern propertyPattern = Pattern.compile("\\$\\{([^}]+)}");
		Matcher matcher = propertyPattern.matcher(trimmed);
		if (matcher.matches()) {
			String key = matcher.group(1);
			String resolved = properties.get(key);
			return resolved == null ? trimmed : resolved;
		}
		return trimmed;
	}

	// Method to scan Gradle build.gradle file for dependencies
	private List<DependencyCoordinate> scanGradleDependencies(Path gradleFilePath) throws Exception {
		List<DependencyCoordinate> dependencies = new ArrayList<>();
		String content = Files.readString(gradleFilePath, StandardCharsets.UTF_8);
		String regex = "[\\\"']([\\w\\.-]+):([\\w\\.-]+):([^\\\"'\\)\\s]+)[\\\"']";
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(content);
		while (matcher.find()) {
			dependencies.add(new DependencyCoordinate(
					matcher.group(1),
					matcher.group(2),
					matcher.group(3),
					"gradle"));
		}
		return dependencies;
	}
}
