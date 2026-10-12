package com.springrag.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Extracts the readable text of an HTML page. Scripts, styles and embedded content are dropped, block elements
 * become line breaks. Nothing is executed or rendered: the result is plain text only.
 */
final class HtmlExtractor {

    private HtmlExtractor() {
    }

    static String extract(String html) {
        Document doc = Jsoup.parse(html);
        String title = doc.title().strip();
        doc.select("script, style, noscript, template, iframe, object, embed, svg, canvas, head").remove();
        StringBuilder text = new StringBuilder();
        if (!title.isEmpty()) {
            text.append(title).append("\n\n");
        }
        if (doc.body() != null) {
            NodeTraversor.traverse(new NodeVisitor() {
                @Override
                public void head(Node node, int depth) {
                    if (node instanceof TextNode textNode) {
                        text.append(textNode.text());
                    } else if (node instanceof Element element && element.normalName().equals("br")) {
                        text.append('\n');
                    }
                }

                @Override
                public void tail(Node node, int depth) {
                    if (node instanceof Element element && element.isBlock()) {
                        text.append('\n');
                    }
                }
            }, doc.body());
        }
        return text.toString();
    }
}
