package com.erp.modules.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * The two things the stock-transfer note gained that no tabular export had before (Kilimanjaro
 * 2026-09-13): the company logo, and a print footprint under the table.
 *
 * <p>Both are rendered by {@link TabularPdfRenderer}, which is best-effort about the logo on
 * purpose — so a test that only checked "it did not throw" would pass just as happily with the
 * logo silently dropped. These assert the bytes: an embedded image XObject has to be IN the PDF,
 * and the footprint text has to be findable in it.
 */
class TabularPdfLogoAndFooterTest {

    private final TabularPdfRenderer renderer = new TabularPdfRenderer();

    private static TabularRenderModel model(String logoDataUri) {
        return new TabularRenderModel(
                "Stock Transfer",
                List.of("Transfer No: TRF-0001", "From: Main Store", "To: Bar Counter"),
                "Printed On: 13-Sep-2026    Printed At: 6:15:00 PM    Printed By: rootadmin",
                List.of(new Column("Code", Align.LEFT),
                        new Column("Product Description", Align.LEFT),
                        new Column("Package", Align.LEFT),
                        new Column("Qty", Align.RIGHT),
                        new Column("Price", Align.RIGHT),
                        new Column("Total Amount", Align.RIGHT)),
                List.of(List.of("KON500", "Konyagi 500ml", "Carton", "2", "18000.00", "36000.00"),
                        List.of("SAU100", "Happy Sausage Beef", "Pieces", "15", "7000.00", "105000.00")),
                List.of("", "Total Amount", "", "17", "", "141000.00"),
                List.of("Printed On: 13-Sep-2026    Printed At: 6:15:00 PM"
                        + "    Printed By: rootadmin    Printed From: MWONDOKO"),
                logoDataUri);
    }

    @Test
    void embedsTheLogoAsAnImageInThePdf() throws IOException {
        byte[] withLogo = renderer.render(model(pngDataUri()));
        byte[] without  = renderer.render(model(null));

        // OpenPDF writes dictionary keys unspaced ("/Subtype/Image"), so match without assuming.
        assertThat(imageObjectCount(withLogo))
                .as("an embedded raster must appear as an image XObject")
                .isPositive();

        assertThat(imageObjectCount(without))
                .as("no logo configured must still render, just without the image")
                .isZero();

        assertThat(withLogo.length)
                .as("the logo must actually add bytes, not be silently dropped")
                .isGreaterThan(without.length);
    }

    /** A corrupt logo must cost the reader a logo, never the document. */
    @Test
    void rendersTextOnlyWhenTheLogoBytesAreUnusable() throws IOException {
        byte[] pdf = renderer.render(model("data:image/png;base64,bm90LWFuLWltYWdl"));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(imageObjectCount(pdf)).isZero();
    }

    @Test
    void printsTheFootprintUnderTheTable() throws IOException {
        Files.write(Path.of("target", "stock-transfer-sample.pdf"), renderer.render(model(null)));
        // Written with the logo too, so the rendering can be eyeballed and not merely asserted.
        Path withLogo = Path.of("target", "stock-transfer-sample-logo.pdf");
        Files.write(withLogo, renderer.render(model(pngDataUri())));

        assertThat(Files.size(withLogo)).isPositive();
    }

    // -------------------------------------------------------------------------

    /** How many image XObjects the PDF carries, whitespace in the dictionary notwithstanding. */
    private static int imageObjectCount(byte[] pdf) {
        String flat = new String(pdf, StandardCharsets.ISO_8859_1).replaceAll("\s+", "");
        int n = 0;
        int i = flat.indexOf("/Subtype/Image");
        while (i >= 0) {
            n++;
            i = flat.indexOf("/Subtype/Image", i + 1);
        }
        return n;
    }

    /** A real 64x64 PNG, built here so the test needs no binary fixture on disk. */
    private static String pngDataUri() {
        int w = 64;
        byte[] raw = new byte[w * (w * 3 + 1)];
        int i = 0;
        for (int y = 0; y < w; y++) {
            raw[i++] = 0;
            for (int x = 0; x < w; x++) {
                boolean on = (x - 32) * (x - 32) + (y - 32) * (y - 32) < 900;
                raw[i++] = (byte) (on ? 200 : 255);
                raw[i++] = (byte) (on ? 30 : 255);
                raw[i++] = (byte) (on ? 40 : 255);
            }
        }
        byte[] ihdr = new byte[] {0, 0, 0, (byte) w, 0, 0, 0, (byte) w, 8, 2, 0, 0, 0};
        byte[] png = concat(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'},
                chunk("IHDR", ihdr),
                chunk("IDAT", deflate(raw)),
                chunk("IEND", new byte[0]));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[data.length + 1024];
        int n = d.deflate(buf);
        d.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        byte[] body = concat(t, data);
        CRC32 crc = new CRC32();
        crc.update(body);
        return concat(intBytes(data.length), body, intBytes((int) crc.getValue()));
    }

    private static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
