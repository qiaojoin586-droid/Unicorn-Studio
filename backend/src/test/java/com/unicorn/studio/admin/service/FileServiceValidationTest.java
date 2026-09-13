package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-17（图片解压炸弹）与 S-8（文档内容校验）的回归测试。
 * <p>
 * <b>为什么必须用「手工构造的巨型 PNG」而不是「生成一张大图」</b>：如果测试里先
 * {@code new BufferedImage(20000, 20000)} 再写 PNG，测试进程自己就会先吃掉 1.6GB 堆 ——
 * 那正好复现了我们要防的问题，却让测试没法跑。真正的攻击载荷本来也<b>不需要</b>真的像素数据：
 * PNG 的 IHDR 块里声明 20000×20000 就够了，解码器会照着这个尺寸去分配光栅。
 * 所以这里手工拼一个「只有头部、没有任何像素数据」的 PNG，几百字节即可触发。
 */
class FileServiceValidationTest {

    // ---------- B-17：图片像素上限 ----------

    @Test
    @DisplayName("声明超大像素的 PNG 被拒（解压炸弹），且报错信息给出实际尺寸")
    void rejectsDecompressionBomb() {
        byte[] bomb = pngWithDeclaredSize(20000, 20000); // 4 亿像素，远超 4000 万上限
        assertTrue(bomb.length < 200,
                "攻击载荷本身应当很小（这正是「解压炸弹」的特征），实际 " + bomb.length + " 字节");

        BusinessException e = assertThrows(BusinessException.class,
                () -> FileService.validateImage(new ByteArrayInputStream(bomb)));
        assertTrue(e.getMessage().contains("像素过大"), "实际报错：" + e.getMessage());
        assertTrue(e.getMessage().contains("20000×20000"),
                "报错应给出实际尺寸便于排查，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("尺寸在限制内的正常图片照常通过")
    void acceptsNormalImage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB), "png", out);
        assertDoesNotThrow(() -> FileService.validateImage(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    @DisplayName("恰好越过上限的边界值被拒")
    void rejectsJustOverLimit() {
        // 上限 40_000_000；6325×6325 ≈ 40_005_625，刚刚越界
        byte[] justOver = pngWithDeclaredSize(6325, 6325);
        assertThrows(BusinessException.class,
                () -> FileService.validateImage(new ByteArrayInputStream(justOver)));
    }

    @Test
    @DisplayName("非图片内容被拒（即使扩展名是 .png）")
    void rejectsNonImage() {
        InputStream notAnImage = new ByteArrayInputStream("这不是图片".getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> FileService.validateImage(notAnImage));
    }

    // ---------- S-8：文档内容校验 ----------

    @Test
    @DisplayName("伪装成 .pdf 的任意字节被拒")
    void rejectsFakePdf() {
        InputStream fake = new ByteArrayInputStream("MZ this is actually an exe".getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> FileService.validateDocument("pdf", fake));
    }

    @Test
    @DisplayName("合法 PDF / ZIP / OOXML / OLE 头放行")
    void acceptsRealDocumentHeaders() {
        assertDoesNotThrow(() -> FileService.validateDocument("pdf",
                new ByteArrayInputStream("%PDF-1.7\n...".getBytes(StandardCharsets.UTF_8))));

        // PK\x03\x04 —— zip 与 docx/xlsx/pptx 共用同一魔数
        byte[] zipHeader = {'P', 'K', 0x03, 0x04, 0, 0, 0, 0};
        assertDoesNotThrow(() -> FileService.validateDocument("zip", new ByteArrayInputStream(zipHeader)));
        assertDoesNotThrow(() -> FileService.validateDocument("docx", new ByteArrayInputStream(zipHeader)));

        // D0 CF 11 E0 A1 B1 1A E1 —— 旧版 Office 复合文档
        byte[] oleHeader = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertDoesNotThrow(() -> FileService.validateDocument("doc", new ByteArrayInputStream(oleHeader)));
        assertDoesNotThrow(() -> FileService.validateDocument("xls", new ByteArrayInputStream(oleHeader)));
    }

    @Test
    @DisplayName("txt/md 无固定魔数：含二进制内容的被拒，正常文本放行")
    void validatesPlainText() {
        assertDoesNotThrow(() -> FileService.validateDocument("md",
                new ByteArrayInputStream("# 标题\n\n正文内容\n".getBytes(StandardCharsets.UTF_8))));

        byte[] withNul = {'h', 'i', 0x00, 'x'};
        assertThrows(BusinessException.class,
                () -> FileService.validateDocument("txt", new ByteArrayInputStream(withNul)));

        byte[] withControl = {'h', 'i', 0x07, 'x'}; // BEL 控制字符
        assertThrows(BusinessException.class,
                () -> FileService.validateDocument("txt", new ByteArrayInputStream(withControl)));
    }

    // ---------- 辅助：手工构造只含 IHDR 的 PNG ----------

    /**
     * 构造仅含「签名 + IHDR + IEND」的 PNG，可声明任意宽高而几乎不占字节。
     * IHDR 是解码器读取尺寸的唯一来源，因此这足以触发「按声明尺寸分配光栅」的行为。
     */
    private static byte[] pngWithDeclaredSize(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
        writeInt(ihdrData, width);
        writeInt(ihdrData, height);
        ihdrData.write(8);   // bit depth
        ihdrData.write(2);   // color type: truecolor
        ihdrData.write(0);   // compression
        ihdrData.write(0);   // filter
        ihdrData.write(0);   // interlace
        writeChunk(out, "IHDR", ihdrData.toByteArray());

        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] typeAndData = new byte[4 + data.length];
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, typeAndData, 0, 4);
        System.arraycopy(data, 0, typeAndData, 4, data.length);
        out.writeBytes(typeAndData);

        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        writeInt(out, (int) crc.getValue());
    }
}
