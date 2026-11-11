package com.mfb.lending.module.loanoffer.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a simple text PDF so the agreement bytes can be hashed and retrieved.
 * The rendered text is also stored beside the file.
 */
public final class PdfAgreementWriter {

    private static final int LINES_PER_PAGE = 46;
    private static final int LINE_WIDTH = 90;

    private PdfAgreementWriter() {
    }

    public static byte[] write(String title, String text) {
        List<String> lines = wrap(title, text);
        int pages = Math.max(1, (lines.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        List<byte[]> contents = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            int from = page * LINES_PER_PAGE;
            int to = Math.min(lines.size(), from + LINES_PER_PAGE);
            contents.add(content(lines.subList(from, to)));
        }
        return assemble(contents);
    }

    private static List<String> wrap(String title, String text) {
        List<String> lines = new ArrayList<>();
        lines.add(title == null ? "Loan agreement" : title);
        lines.add("");
        String source = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        for (String paragraph : source.split("\n", -1)) {
            String remaining = paragraph;
            if (remaining.isEmpty()) {
                lines.add("");
                continue;
            }
            while (!remaining.isEmpty()) {
                int take = Math.min(LINE_WIDTH, remaining.length());
                if (take < remaining.length()) {
                    int breakAt = remaining.lastIndexOf(' ', take);
                    if (breakAt > 20) {
                        take = breakAt;
                    }
                }
                lines.add(remaining.substring(0, take).trim());
                remaining = remaining.substring(take).trim();
            }
        }
        return lines;
    }

    private static byte[] content(List<String> lines) {
        StringBuilder stream = new StringBuilder();
        stream.append("BT\n/F1 10 Tf\n48 800 Td\n14 TL\n");
        for (String line : lines) {
            stream.append('(').append(escape(line)).append(") '\n");
        }
        stream.append("ET\n");
        return stream.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '(' || character == ')') {
                escaped.append('\\').append(character);
            } else if (character >= 32 && character <= 126) {
                escaped.append(character);
            } else {
                escaped.append('?');
            }
        }
        return escaped.toString();
    }

    private static byte[] assemble(List<byte[]> contents) {
        int pages = contents.size();
        int fontId = 3;
        int firstPageId = 4;
        List<String> objects = new ArrayList<>();
        objects.add(null);
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder("<< /Type /Pages /Count ").append(pages).append(" /Kids [");
        for (int page = 0; page < pages; page++) {
            kids.append(firstPageId + page * 2).append(" 0 R ");
        }
        kids.append("] >>");
        objects.add(kids.toString());
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int page = 0; page < pages; page++) {
            int pageId = firstPageId + page * 2;
            int contentId = pageId + 1;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents "
                    + contentId + " 0 R /Resources << /Font << /F1 " + fontId + " 0 R >> >> >>");
            objects.add(null);
        }
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        int contentCursor = 0;
        for (int id = 1; id < objects.size(); id++) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            String body = objects.get(id);
            if (body == null) {
                byte[] stream = contents.get(contentCursor++);
                pdf.append(id).append(" 0 obj\n<< /Length ").append(stream.length).append(" >>\nstream\n");
                pdf.append(new String(stream, StandardCharsets.US_ASCII));
                pdf.append("endstream\nendobj\n");
            } else {
                pdf.append(id).append(" 0 obj\n").append(body).append("\nendobj\n");
            }
        }
        int xref = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(objects.size()).append('\n');
        pdf.append("0000000000 65535 f \n");
        for (int id = 1; id < offsets.size(); id++) {
            pdf.append(String.format("%010d 00000 n \n", offsets.get(id)));
        }
        pdf.append("trailer << /Size ").append(objects.size()).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
