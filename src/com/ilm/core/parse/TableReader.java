package com.ilm.core.parse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Minimal .xlsx and .csv reader (JDK only, no Apache POI). Reads the first worksheet.
 * Cell values are String or Double. Hardened against XXE and zip bombs.
 */
public final class TableReader {
    private static final long MAX_UNZIPPED = 80L * 1024 * 1024;

    private TableReader() {}

    public static List<List<Object>> readXlsx(InputStream in) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry ze;
            byte[] buf = new byte[8192];
            while ((ze = zip.getNextEntry()) != null) {
                String name = ze.getName();
                boolean wanted = name.equals("xl/sharedStrings.xml") || name.matches("xl/worksheets/sheet\\d+\\.xml");
                ByteArrayOutputStream bos = wanted ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNZIPPED) throw new IOException("Spreadsheet is too large");
                    if (bos != null) bos.write(buf, 0, n);
                }
                if (bos != null) entries.put(name, bos.toByteArray());
            }
        }
        String sheet = entries.keySet().stream().filter(k -> k.startsWith("xl/worksheets/"))
                .min((a, b) -> Integer.compare(sheetNo(a), sheetNo(b))).orElse(null);
        if (sheet == null) throw new IOException("No worksheet found - is this a real .xlsx file?");
        try {
            List<String> shared = entries.containsKey("xl/sharedStrings.xml")
                    ? readSharedStrings(entries.get("xl/sharedStrings.xml")) : List.of();
            return readSheet(entries.get(sheet), shared);
        } catch (javax.xml.stream.XMLStreamException | RuntimeException e) {
            throw new IOException("Spreadsheet content is damaged: " + e.getMessage(), e);
        }
    }

    private static int sheetNo(String name) {
        return Integer.parseInt(name.replaceAll("\\D+", ""));
    }

    private static XMLStreamReader xml(byte[] data) throws javax.xml.stream.XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(data));
    }

    private static List<String> readSharedStrings(byte[] data) throws javax.xml.stream.XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = xml(data);
        StringBuilder sb = null;
        boolean inT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("si")) sb = new StringBuilder();
                else if (r.getLocalName().equals("t")) inT = true;
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                if (inT && sb != null) sb.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("t")) inT = false;
                else if (r.getLocalName().equals("si") && sb != null) { out.add(sb.toString()); sb = null; }
            }
        }
        return out;
    }

    private static List<List<Object>> readSheet(byte[] data, List<String> shared) throws javax.xml.stream.XMLStreamException {
        List<List<Object>> rows = new ArrayList<>();
        XMLStreamReader r = xml(data);
        TreeMap<Integer, Object> row = null;
        String type = null;
        int col = -1, nextCol = 0;
        StringBuilder val = null;
        boolean capture = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("row")) { row = new TreeMap<>(); nextCol = 0; }
                else if (n.equals("c") && row != null) {
                    type = r.getAttributeValue(null, "t");
                    String ref = r.getAttributeValue(null, "r");
                    col = ref == null ? nextCol : colIndex(ref);
                    nextCol = col + 1;
                    val = new StringBuilder();
                } else if ((n.equals("v") || n.equals("t")) && val != null) capture = true;
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                if (capture) val.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("v") || n.equals("t")) capture = false;
                else if (n.equals("c") && row != null && val != null) {
                    String s = val.toString();
                    Object v = null;
                    if (!s.isEmpty()) {
                        if ("s".equals(type)) {
                            int i = Integer.parseInt(s.trim());
                            v = i >= 0 && i < shared.size() ? shared.get(i) : "";
                        } else if (type == null || "n".equals(type)) {
                            try { v = Double.valueOf(s.trim()); } catch (NumberFormatException ex) { v = s; }
                        } else v = s;
                    }
                    if (v != null) row.put(col, v);
                    val = null;
                } else if (n.equals("row") && row != null) {
                    List<Object> list = new ArrayList<>();
                    if (!row.isEmpty()) {
                        int max = row.lastKey();
                        for (int i = 0; i <= max; i++) list.add(row.get(i));
                    }
                    rows.add(list);
                    row = null;
                }
            }
        }
        return rows;
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
        }
        return c - 1;
    }

    /** RFC 4180 style CSV; separator is auto-detected between comma and semicolon. */
    public static List<List<Object>> readCsv(BufferedReader in) throws IOException {
        StringBuilder all = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            all.append(buf, 0, n);
            if (all.length() > MAX_UNZIPPED) throw new IOException("CSV is too large");
        }
        String text = all.toString();
        if (text.startsWith("﻿")) text = text.substring(1);
        int firstNl = text.indexOf('\n');
        String head = firstNl < 0 ? text : text.substring(0, firstNl);
        char sep = head.chars().filter(c -> c == ';').count() > head.chars().filter(c -> c == ',').count() ? ';' : ',';
        List<List<Object>> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
            } else if (c == '"' && cell.length() == 0) quoted = true;
            else if (c == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n') {
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else if (c != '\r') cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
