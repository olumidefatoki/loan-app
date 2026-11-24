package com.mfb.lending.module.reporting.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.mfb.lending.module.reporting.calculation.ReportMask;
import com.mfb.lending.module.reporting.domain.ExportFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

public final class ReportWriters {

    private ReportWriters() {
    }

    public static long write(ExportFormat format, Path path, List<String> headers, Consumer<Consumer<List<String>>> producer) throws IOException {
        return switch (format) {
            case CSV -> csv(path, headers, producer);
            case XLSX -> xlsx(path, headers, producer);
            case PDF -> pdf(path, headers, producer);
        };
    }

    private static long csv(Path path, List<String> headers, Consumer<Consumer<List<String>>> producer) throws IOException {
        long[] rows = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(csvLine(headers));
            writer.newLine();
            producer.accept(row -> {
                try {
                    writer.write(csvLine(row));
                    writer.newLine();
                    rows[0]++;
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
        return rows[0];
    }

    private static long xlsx(Path path, List<String> headers, Consumer<Consumer<List<String>>> producer) throws IOException {
        long[] rows = {0};
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Report");
            int[] index = {0};
            writeRow(sheet, index[0]++, headers);
            producer.accept(row -> {
                writeRow(sheet, index[0]++, row);
                rows[0]++;
            });
            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
        return rows[0];
    }

    private static long pdf(Path path, List<String> headers, Consumer<Consumer<List<String>>> producer) throws IOException {
        long[] rows = {0};
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            String[] headerLine = {pdfLine(headers)};
            float[] y = {0};
            PDPage[] page = {null};
            PDPageContentStream[] stream = {null};
            Runnable nextPage = () -> {
                try {
                    if (stream[0] != null) {
                        stream[0].close();
                    }
                    page[0] = new PDPage(landscape);
                    document.addPage(page[0]);
                    stream[0] = new PDPageContentStream(document, page[0]);
                    y[0] = landscape.getHeight() - 36;
                    draw(stream[0], font, 36, y[0], headerLine[0]);
                    y[0] -= 14;
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            };
            nextPage.run();
            producer.accept(row -> {
                try {
                    if (y[0] < 36) {
                        nextPage.run();
                    }
                    draw(stream[0], font, 36, y[0], pdfLine(row));
                    y[0] -= 12;
                    rows[0]++;
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            if (stream[0] != null) {
                stream[0].close();
            }
            document.save(path.toFile());
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
        return rows[0];
    }

    private static void draw(PDPageContentStream stream, PDType1Font font, float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(font, 8);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static void writeRow(Sheet sheet, int index, List<String> values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i).setCellValue(values.get(i) == null ? "" : values.get(i));
        }
    }

    private static String csvLine(List<String> values) {
        return String.join(",", values.stream().map(value -> ReportMask.csv(value, ReportMask.plainNumber(value))).toList());
    }

    private static String pdfLine(List<String> values) {
        return values.stream().map(ReportWriters::pdfCell).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String pdfCell(String value) {
        String safe = value == null ? "" : value;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < safe.length() && text.length() < 16; i++) {
            char character = safe.charAt(i);
            text.append(character >= 32 && character <= 126 ? character : ' ');
        }
        return text.toString();
    }
}
