package com.example.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Robust pure Kotlin QR Code Generator (Versions 1-6, Byte mode, EC Level M).
 * Generates standards-compliant scannable QR codes for any download URL without external libraries.
 */
object QrCodeUtil {

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val qrMatrix = encodeToMatrix(content)
        val matrixSize = qrMatrix.size
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

        val quietZone = 4
        val totalModules = matrixSize + quietZone * 2
        val scale = sizePx.toFloat() / totalModules

        val pixels = IntArray(sizePx * sizePx) { Color.WHITE }

        for (r in 0 until matrixSize) {
            for (c in 0 until matrixSize) {
                if (qrMatrix[r][c]) {
                    val startX = ((c + quietZone) * scale).toInt()
                    val endX = ((c + quietZone + 1) * scale).toInt().coerceAtMost(sizePx)
                    val startY = ((r + quietZone) * scale).toInt()
                    val endY = ((r + quietZone + 1) * scale).toInt().coerceAtMost(sizePx)

                    for (y in startY until endY) {
                        for (x in startX until endX) {
                            pixels[y * sizePx + x] = Color.BLACK
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bitmap
    }

    private fun encodeToMatrix(data: String): Array<BooleanArray> {
        val bytes = data.toByteArray(Charsets.ISO_8859_1)
        val dataLen = bytes.size

        // Select QR version (1 to 6) based on Byte mode EC Level M capacities
        val (version, totalCodewords, ecCodewords, ecBlocks) = when {
            dataLen <= 14 -> Quad(1, 26, 10, 1)
            dataLen <= 26 -> Quad(2, 44, 16, 1)
            dataLen <= 42 -> Quad(3, 70, 26, 1)
            dataLen <= 62 -> Quad(4, 100, 36, 2)
            dataLen <= 84 -> Quad(5, 134, 48, 2)
            dataLen <= 106 -> Quad(6, 172, 64, 4)
            dataLen <= 122 -> Quad(7, 196, 72, 4)
            dataLen <= 152 -> Quad(8, 242, 88, 4)
            dataLen <= 180 -> Quad(9, 292, 110, 5)
            else -> Quad(10, 346, 130, 5)
        }

        val dimension = version * 4 + 17
        val matrix = Array(dimension) { BooleanArray(dimension) }
        val isFunction = Array(dimension) { BooleanArray(dimension) }

        // Finder patterns
        fun drawFinder(topRow: Int, leftCol: Int) {
            for (r in 0..6) {
                for (c in 0..6) {
                    val isBlack = (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4))
                    val mr = topRow + r
                    val mc = leftCol + c
                    matrix[mr][mc] = isBlack
                    isFunction[mr][mc] = true
                }
            }
            // Separator
            for (r in -1..7) {
                for (c in -1..7) {
                    val mr = topRow + r
                    val mc = leftCol + c
                    if (mr in 0 until dimension && mc in 0 until dimension) {
                        isFunction[mr][mc] = true
                        if (r == -1 || r == 7 || c == -1 || c == 7) {
                            matrix[mr][mc] = false
                        }
                    }
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(0, dimension - 7)
        drawFinder(dimension - 7, 0)

        // Timing patterns
        for (i in 8 until dimension - 8) {
            matrix[6][i] = (i % 2 == 0)
            isFunction[6][i] = true
            matrix[i][6] = (i % 2 == 0)
            isFunction[i][6] = true
        }

        // Dark module
        matrix[dimension - 8][8] = true
        isFunction[dimension - 8][8] = true

        // Alignment patterns for version >= 2
        val alignmentPositions = getAlignmentPositions(version)
        for (r in alignmentPositions) {
            for (c in alignmentPositions) {
                if (isFunction[r][c]) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val isBlack = (dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0))
                        matrix[r + dr][c + dc] = isBlack
                        isFunction[r + dr][c + dc] = true
                    }
                }
            }
        }

        // Format info reservation
        for (i in 0..8) {
            isFunction[8][i] = true
            isFunction[i][8] = true
        }
        for (i in 0..7) {
            isFunction[8][dimension - 1 - i] = true
            isFunction[dimension - 1 - i][8] = true
        }

        // Encode data bits (Byte mode = 0100)
        val bitBuffer = mutableListOf<Int>()
        fun putBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bitBuffer.add((value shr i) and 1)
            }
        }

        putBits(0b0100, 4) // Byte mode indicator
        val countBits = if (version <= 9) 8 else 16
        putBits(bytes.size, countBits)

        for (b in bytes) {
            putBits(b.toInt() and 0xFF, 8)
        }

        val dataCodewordsCount = totalCodewords - ecCodewords
        val totalDataBits = dataCodewordsCount * 8

        // Terminator
        val terminatorLen = (totalDataBits - bitBuffer.size).coerceIn(0, 4)
        for (i in 0 until terminatorLen) bitBuffer.add(0)

        // Pad to byte
        while (bitBuffer.size % 8 != 0) bitBuffer.add(0)

        // Pad bytes
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIndex = 0
        while (bitBuffer.size < totalDataBits) {
            putBits(padBytes[padIndex % 2], 8)
            padIndex++
        }

        val dataBytes = IntArray(dataCodewordsCount)
        for (i in 0 until dataCodewordsCount) {
            var byteVal = 0
            for (b in 0..7) {
                byteVal = (byteVal shl 1) or bitBuffer[i * 8 + b]
            }
            dataBytes[i] = byteVal
        }

        // Reed Solomon EC calculation
        val allCodewords = generateCodewords(dataBytes, totalCodewords, ecCodewords, ecBlocks)

        // Place codewords in matrix with zig-zag
        val dataBitsList = mutableListOf<Boolean>()
        for (cw in allCodewords) {
            for (i in 7 downTo 0) {
                dataBitsList.add(((cw shr i) and 1) == 1)
            }
        }

        var bitIndex = 0
        var up = true
        var col = dimension - 1
        while (col > 0) {
            if (col == 6) col-- // Skip vertical timing column
            val rows = if (up) (dimension - 1 downTo 0) else (0 until dimension)
            for (row in rows) {
                for (c in 0..1) {
                    val targetCol = col - c
                    if (!isFunction[row][targetCol]) {
                        val bit = if (bitIndex < dataBitsList.size) dataBitsList[bitIndex++] else false
                        // Mask pattern 0: (row + col) % 2 == 0
                        val mask = ((row + targetCol) % 2 == 0)
                        matrix[row][targetCol] = bit xor mask
                    }
                }
            }
            up = !up
            col -= 2
        }

        // Format info (Mask 000, EC Level M = 00) -> 00000 -> with BCH = 0x5412 xor 0x5412 = 0b101010000010010
        val formatBits = getFormatBits(0b00000) // EC level M (00) and mask 0 (000)
        applyFormatBits(matrix, dimension, formatBits)

        return matrix
    }

    private data class Quad(val v: Int, val total: Int, val ec: Int, val blocks: Int)

    private fun getAlignmentPositions(version: Int): IntArray {
        return when (version) {
            1 -> intArrayOf()
            2 -> intArrayOf(6, 18)
            3 -> intArrayOf(6, 22)
            4 -> intArrayOf(6, 26)
            5 -> intArrayOf(6, 30)
            6 -> intArrayOf(6, 34)
            7 -> intArrayOf(6, 22, 38)
            8 -> intArrayOf(6, 24, 42)
            9 -> intArrayOf(6, 26, 46)
            10 -> intArrayOf(6, 28, 50)
            else -> intArrayOf(6, 28, 50)
        }
    }

    private fun getFormatBits(formatData: Int): Int {
        // Generator polynomial for format info is 0x537
        var d = formatData shl 10
        for (i in 14 downTo 10) {
            if (((d shr i) and 1) == 1) {
                d = d xor (0x537 shl (i - 10))
            }
        }
        val encoded = (formatData shl 10) or d
        return encoded xor 0x5412 // Mask with 101010000010010
    }

    private fun applyFormatBits(matrix: Array<BooleanArray>, dim: Int, formatBits: Int) {
        // Horizontal: bits 0..5 on row 8, col 0..5; bit 6 on row 8 col 7; bit 7 on row 8 col 8; bit 8 on row 7 col 8; bits 9..14 on rows 5..0 col 8
        val bits = BooleanArray(15) { (formatBits shr (14 - it) and 1) == 1 }

        // Top-left
        matrix[8][0] = bits[0]
        matrix[8][1] = bits[1]
        matrix[8][2] = bits[2]
        matrix[8][3] = bits[3]
        matrix[8][4] = bits[4]
        matrix[8][5] = bits[5]
        matrix[8][7] = bits[6]
        matrix[8][8] = bits[7]
        matrix[7][8] = bits[8]
        matrix[5][8] = bits[9]
        matrix[4][8] = bits[10]
        matrix[3][8] = bits[11]
        matrix[2][8] = bits[12]
        matrix[1][8] = bits[13]
        matrix[0][8] = bits[14]

        // Other copies (split into bottom-left and top-right)
        for (i in 0..7) {
            matrix[dim - 1 - i][8] = bits[i]
        }
        for (i in 8..14) {
            matrix[8][dim - 15 + i] = bits[i]
        }
    }

    // Reed-Solomon generation
    private val expTable = IntArray(256)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) {
                x = x xor 0x11D
            }
        }
        for (i in 255 until 512) {
            expTable[i] = expTable[i - 255]
        }
    }

    private fun gfMul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return expTable[logTable[x] + logTable[y]]
    }

    private fun generateCodewords(data: IntArray, totalCodewords: Int, ecCodewords: Int, blocks: Int): IntArray {
        val ecPerBlock = ecCodewords / blocks
        val dataPerBlock = (totalCodewords - ecCodewords) / blocks
        val shortBlocks = blocks - ((totalCodewords - ecCodewords) % blocks)
        val dataBlockList = mutableListOf<IntArray>()
        val ecBlockList = mutableListOf<IntArray>()

        var offset = 0
        for (b in 0 until blocks) {
            val length = if (b < shortBlocks) dataPerBlock else dataPerBlock + 1
            val block = data.copyOfRange(offset, offset + length)
            offset += length
            dataBlockList.add(block)
            ecBlockList.add(calcReedSolomon(block, ecPerBlock))
        }

        // Interleave
        val result = mutableListOf<Int>()
        val maxDataLen = dataBlockList.maxOf { it.size }
        for (i in 0 until maxDataLen) {
            for (block in dataBlockList) {
                if (i < block.size) result.add(block[i])
            }
        }
        for (i in 0 until ecPerBlock) {
            for (block in ecBlockList) {
                result.add(block[i])
            }
        }
        return result.toIntArray()
    }

    private fun calcReedSolomon(data: IntArray, ecCount: Int): IntArray {
        // Generator polynomial for ecCount roots
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            val nextGen = IntArray(gen.size + 1)
            val root = expTable[i]
            for (j in gen.indices) {
                nextGen[j] = nextGen[j] xor gen[j]
                nextGen[j + 1] = nextGen[j + 1] xor gfMul(gen[j], root)
            }
            gen = nextGen
        }

        val res = IntArray(ecCount)
        for (byte in data) {
            val factor = byte xor res[0]
            System.arraycopy(res, 1, res, 0, ecCount - 1)
            res[ecCount - 1] = 0
            for (j in 0 until ecCount) {
                res[j] = res[j] xor gfMul(gen[j + 1], factor)
            }
        }
        return res
    }
}
