package com.hingebot;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class UiParser {

    public static List<Node> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return Collections.emptyList();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {}
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            } catch (Exception ignored) {}
            try {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList elements = doc.getElementsByTagName("*");

            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < elements.getLength(); i++) {
                org.w3c.dom.Node domNode = elements.item(i);
                if (domNode instanceof Element elem) {
                    String boundsAttr = elem.getAttribute("bounds");
                    if (boundsAttr == null || boundsAttr.isEmpty()) {
                        continue;
                    }
                    int[] b = parseBounds(boundsAttr);
                    if (b == null) {
                        continue;
                    }
                    String text = elem.getAttribute("text");
                    String desc = elem.getAttribute("content-desc");
                    boolean clickable = "true".equalsIgnoreCase(elem.getAttribute("clickable"));
                    String pkg = elem.getAttribute("package");

                    nodes.add(new Node(text, desc, b[0], b[1], b[2], b[3], clickable, pkg));
                }
            }
            return nodes;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static int[] parseBounds(String bounds) {
        try {
            int firstClose = bounds.indexOf(']');
            int secondOpen = bounds.indexOf('[', firstClose);
            if (firstClose < 0 || secondOpen < 0) return null;

            String firstPart = bounds.substring(1, firstClose);
            String secondPart = bounds.substring(secondOpen + 1, bounds.length() - 1);

            String[] c1 = firstPart.split(",");
            String[] c2 = secondPart.split(",");

            int x1 = Integer.parseInt(c1[0].trim());
            int y1 = Integer.parseInt(c1[1].trim());
            int x2 = Integer.parseInt(c2[0].trim());
            int y2 = Integer.parseInt(c2[1].trim());

            return new int[]{x1, y1, x2, y2};
        } catch (Exception e) {
            return null;
        }
    }

    public static Optional<Node> find(List<Node> nodes, List<String> labels, boolean clickableOnly) {
        if (nodes == null || nodes.isEmpty() || labels == null || labels.isEmpty()) {
            return Optional.empty();
        }

        List<Node> descMatches = new ArrayList<>();
        List<Node> textMatches = new ArrayList<>();

        for (Node node : nodes) {
            String descLower = node.desc() != null ? node.desc().toLowerCase(Locale.ROOT) : "";
            String textLower = node.text() != null ? node.text().toLowerCase(Locale.ROOT) : "";

            for (String label : labels) {
                String lblLower = label.toLowerCase(Locale.ROOT);
                if (!lblLower.isEmpty()) {
                    if (descLower.contains(lblLower)) {
                        descMatches.add(node);
                        break;
                    } else if (textLower.contains(lblLower)) {
                        textMatches.add(node);
                        break;
                    }
                }
            }
        }

        List<Node> candidates = !descMatches.isEmpty() ? descMatches : textMatches;
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        if (clickableOnly) {
            List<Node> clickableCandidates = candidates.stream().filter(Node::clickable).toList();
            if (!clickableCandidates.isEmpty()) {
                candidates = clickableCandidates;
            }
        }

        return candidates.stream().max((a, b) -> {
            int areaDiff = Integer.compare(a.area(), b.area());
            if (areaDiff != 0) {
                return areaDiff;
            }
            return Integer.compare(b.y1(), a.y1());
        });
    }

    public static int[] center(Node node) {
        if (node == null) return new int[]{0, 0};
        return new int[]{node.centerX(), node.centerY()};
    }

    public static boolean containsText(List<Node> nodes, List<String> phrases) {
        if (nodes == null || phrases == null) return false;
        for (Node n : nodes) {
            String textLower = n.text() != null ? n.text().toLowerCase(Locale.ROOT) : "";
            String descLower = n.desc() != null ? n.desc().toLowerCase(Locale.ROOT) : "";
            for (String phrase : phrases) {
                String phraseLower = phrase.toLowerCase(Locale.ROOT);
                if (!phraseLower.isEmpty() && (textLower.contains(phraseLower) || descLower.contains(phraseLower))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String fingerprint(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "empty";
        }
        List<String> pairs = new ArrayList<>();
        for (Node n : nodes) {
            pairs.add((n.text() != null ? n.text() : "") + "|" + (n.desc() != null ? n.desc() : ""));
        }
        Collections.sort(pairs);
        String textToHash = String.join("\n", pairs);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(textToHash.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(textToHash.hashCode());
        }
    }
}
