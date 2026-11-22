package com.hingebot;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class UiParserTest {

    private String loadResource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Resource not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("Failed to load resource " + name + ": " + e.getMessage());
            return "";
        }
    }

    @Test
    public void testParseProfileXml() {
        String xml = loadResource("profile.xml");
        List<Node> nodes = UiParser.parse(xml);
        assertFalse(nodes.isEmpty());
        assertEquals(4, nodes.size());

        Node textNode = nodes.get(1);
        assertEquals("Jane, 26", textNode.text());

        Node likeNode = nodes.get(2);
        assertEquals("Like", likeNode.desc());
        assertTrue(likeNode.clickable());
    }

    @Test
    public void testFindByDescAndByText() {
        String xml = loadResource("profile.xml");
        List<Node> nodes = UiParser.parse(xml);

        Optional<Node> likeNode = UiParser.find(nodes, List.of("Like"), true);
        assertTrue(likeNode.isPresent());
        assertEquals("Like", likeNode.get().desc());

        Optional<Node> textNode = UiParser.find(nodes, List.of("Jane"), false);
        assertTrue(textNode.isPresent());
        assertEquals("Jane, 26", textNode.get().text());
    }

    @Test
    public void testFindEdgeCasesNullOrEmptyInputs() {
        assertTrue(UiParser.find(null, List.of("Like"), true).isEmpty());
        assertTrue(UiParser.find(Collections.emptyList(), List.of("Like"), true).isEmpty());
        assertTrue(UiParser.find(List.of(new Node("a", "b", 0, 0, 10, 10, true, "p")), null, true).isEmpty());
        assertTrue(UiParser.find(List.of(new Node("a", "b", 0, 0, 10, 10, true, "p")), Collections.emptyList(), true).isEmpty());
    }

    @Test
    public void testFindFallbackToNonClickableWhenNoClickableExist() {
        Node nonClickable = new Node("Skip", "Skip", 0, 0, 100, 100, false, "pkg");
        Optional<Node> found = UiParser.find(List.of(nonClickable), List.of("Skip"), true);
        assertTrue(found.isPresent());
        assertFalse(found.get().clickable());
    }

    @Test
    public void testPreferClickableAndLargestAreaTieBreak() {
        String xml = """
            <?xml version='1.0' encoding='UTF-8' ?>
            <hierarchy>
              <node bounds="[0,0][100,100]" content-desc="Heart" clickable="false" package="co.hinge.app" />
              <node bounds="[0,0][50,50]" content-desc="Heart" clickable="true" package="co.hinge.app" />
              <node bounds="[0,0][200,200]" content-desc="Heart" clickable="true" package="co.hinge.app" />
            </hierarchy>
            """;
        List<Node> nodes = UiParser.parse(xml);

        Optional<Node> found = UiParser.find(nodes, List.of("Heart"), true);
        assertTrue(found.isPresent());
        assertEquals(40000, found.get().area()); // 200x200
        assertTrue(found.get().clickable());
    }

    @Test
    public void testTieBreakLowestYWhenAreasAreEqual() {
        String xml = """
            <?xml version='1.0' encoding='UTF-8' ?>
            <hierarchy>
              <node bounds="[0,500][100,600]" content-desc="Skip" clickable="true" package="co.hinge.app" />
              <node bounds="[0,100][100,200]" content-desc="Skip" clickable="true" package="co.hinge.app" />
            </hierarchy>
            """;
        List<Node> nodes = UiParser.parse(xml);

        Optional<Node> found = UiParser.find(nodes, List.of("Skip"), true);
        assertTrue(found.isPresent());
        assertEquals(100, found.get().y1()); // lowest y (smaller y1) preferred
    }

    @Test
    public void testCenterCalculation() {
        Node node = new Node("text", "desc", 100, 200, 300, 400, true, "pkg");
        int[] c = UiParser.center(node);
        assertArrayEquals(new int[]{200, 300}, c);

        assertArrayEquals(new int[]{0, 0}, UiParser.center(null));
    }

    @Test
    public void testMalformedXmlReturnsEmptyList() {
        List<Node> nodes = UiParser.parse("THIS IS NOT XML <>>>");
        assertTrue(nodes.isEmpty());

        List<Node> nullNodes = UiParser.parse(null);
        assertTrue(nullNodes.isEmpty());
    }

    @Test
    public void testXxeDoctypeInputReturnsEmptyList() {
        String xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <hierarchy>
              <node bounds="[0,0][100,100]" text="&xxe;" clickable="true" package="co.hinge.app" />
            </hierarchy>
            """;
        List<Node> nodes = UiParser.parse(xxeXml);
        assertTrue(nodes.isEmpty());
    }

    @Test
    public void testContainsTextOutOfLikesDetection() {
        String profileXml = loadResource("profile.xml");
        List<Node> profileNodes = UiParser.parse(profileXml);
        assertFalse(UiParser.containsText(profileNodes, List.of("out of likes", "no likes left")));

        String outOfLikesXml = loadResource("out_of_likes.xml");
        List<Node> outOfLikesNodes = UiParser.parse(outOfLikesXml);
        assertTrue(UiParser.containsText(outOfLikesNodes, List.of("out of likes", "no likes left")));

        assertFalse(UiParser.containsText(null, List.of("test")));
        assertFalse(UiParser.containsText(profileNodes, null));
    }

    @Test
    public void testFingerprintDiffersBetweenFixtures() {
        String profileXml = loadResource("profile.xml");
        String outOfLikesXml = loadResource("out_of_likes.xml");
        String sheetXml = loadResource("send_like_sheet.xml");

        String fp1 = UiParser.fingerprint(UiParser.parse(profileXml));
        String fp2 = UiParser.fingerprint(UiParser.parse(outOfLikesXml));
        String fp3 = UiParser.fingerprint(UiParser.parse(sheetXml));

        assertNotEquals(fp1, fp2);
        assertNotEquals(fp1, fp3);
        assertNotEquals(fp2, fp3);

        assertEquals("empty", UiParser.fingerprint(null));
        assertEquals("empty", UiParser.fingerprint(Collections.emptyList()));
    }
}
