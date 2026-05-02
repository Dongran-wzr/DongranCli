package com.dr.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Comparator;
import java.util.Locale;

public class ReadabilityExtractor {
    public String extract(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html, baseUrl);

        Element semantic = pickSemanticNode(doc);
        if (semantic != null) {
            String text = clean(semantic.text());
            if (text.length() >= 120) {
                return trim(text, 6000);
            }
        }

        Element scored = pickByLinkDensity(doc);
        if (scored != null) {
            return trim(clean(scored.text()), 6000);
        }
        return trim(clean(doc.body() == null ? "" : doc.body().text()), 6000);
    }

    private Element pickSemanticNode(Document doc) {
        Elements candidates = doc.select("article, main, [role=main], section");
        return candidates.stream()
                .max(Comparator.comparingInt(e -> clean(e.text()).length()))
                .orElse(null);
    }

    private Element pickByLinkDensity(Document doc) {
        Elements nodes = doc.select("article, section, div, p");
        Element best = null;
        double bestScore = -1;
        for (Element e : nodes) {
            String text = clean(e.text());
            if (text.length() < 80) {
                continue;
            }
            int textLen = text.length();
            int linkLen = clean(e.select("a").text()).length();
            double density = textLen == 0 ? 1.0 : (double) linkLen / (double) textLen;
            double semanticWeight = semanticWeight(e.tagName());
            double score = textLen * (1.0 - density) * semanticWeight;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private double semanticWeight(String tag) {
        String t = tag == null ? "" : tag.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "article" -> 1.25;
            case "section" -> 1.15;
            case "div" -> 1.0;
            case "p" -> 0.9;
            default -> 0.8;
        };
    }

    private String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private String trim(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
