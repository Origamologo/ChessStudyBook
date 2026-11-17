package com.example.chessstudy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// --- WARM WOOD & LIGHT BROWN PALETTE ---
val SoftLightBrownBg = Color(0xFFE6D5C3)   // Soft warm light brown for the main background
val ParchmentCardBg = Color(0xFFF5EBE0)    // Cream parchment for the book reader & notation card
val PaperInnerBg = Color(0xFFFAF5EF)       // Soft reading surface inside the book box
val WoodPanelBg = Color(0xFF8B5A2B)        // Warm medium-dark wood surrounding the chessboard
val DarkWalnutBorder = Color(0xFF4A2C11)   // Deep walnut border for the chessboard frame
val WoodLightSquare = Color(0xFFF0D9B5)    // Classic maple light chess square
val WoodDarkSquare = Color(0xFFB58863)     // Classic mahogany dark chess square
val WoodSelectedSquare = Color(0xFFE6B800) // Warm amber highlight for selected square
val WarmBrownButton = Color(0xFF6F4E37)    // Coffee/wood tone for primary buttons
val SoftTanButton = Color(0xFFD7C0AE)      // Light tan for secondary/outlined buttons
val DeepEspressoText = Color(0xFF2C1B10)   // Dark brown-black for comfortable reading

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SoftLightBrownBg
                ) {
                    ChessStudyScreen()
                }
            }
        }
    }
}

fun initialBoardString(): String =
    "rnbqkbnr" +
            "pppppppp" +
            "        " +
            "        " +
            "        " +
            "        " +
            "PPPPPPPP" +
            "RNBQKBNR"

fun emptyBoardString(): String = " ".repeat(64)

// --- TREE-BASED PGN MODEL (With Symbols & Written Notes in Brackets) ---
data class MoveNode(
    val id: Int,
    val parentId: Int?,
    val san: String,
    val board: String,
    val ply: Int, // Even (0, 2, 4...) = White to move; Odd (1, 3, 5...) = Black to move
    val children: List<Int> = emptyList(),
    val nag: String = "",     // Common symbols: !, ?, !?, ?!, !!, +/=, +/-, +-, =, ∞, =/∞, -/+, -+
    val comment: String = ""  // Written notes placed inside { ... }
)

fun encodePipeSafe(s: String): String =
    s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

fun decodePipeSafe(s: String): String =
    s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")

fun serializeNodes(nodes: Map<Int, MoveNode>): ArrayList<String> {
    return ArrayList(nodes.values.map { n ->
        "${n.id}|${n.parentId ?: -1}|${encodePipeSafe(n.san)}|${n.board}|${n.ply}|" +
                "${n.children.joinToString(",")}|${encodePipeSafe(n.nag)}|${encodePipeSafe(n.comment)}"
    })
}

fun deserializeNodes(lines: List<String>): Map<Int, MoveNode> {
    val map = mutableMapOf<Int, MoveNode>()
    for (line in lines) {
        val parts = line.split("|")
        if (parts.size >= 6) {
            val id = parts[0].toInt()
            val parentId = parts[1].toInt().let { if (it == -1) null else it }
            val san = decodePipeSafe(parts[2])
            val board = parts[3]
            val ply = parts[4].toInt()
            val children = if (parts[5].isEmpty()) emptyList() else parts[5].split(",").map { it.toInt() }
            val nag = if (parts.size >= 7) decodePipeSafe(parts[6]) else ""
            val comment = if (parts.size >= 8) decodePipeSafe(parts[7]) else ""
            map[id] = MoveNode(id, parentId, san, board, ply, children, nag, comment)
        }
    }
    return map
}

fun collectSubtreeIds(nodeId: Int, nodes: Map<Int, MoveNode>): Set<Int> {
    val result = mutableSetOf(nodeId)
    val node = nodes[nodeId] ?: return result
    for (childId in node.children) {
        result.addAll(collectSubtreeIds(childId, nodes))
    }
    return result
}

// --- FULL LEGAL CHESS MOVE, KING SAFETY & REAL SAN GENERATOR ---
fun isPathClear(board: String, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
    val dr = (r2 - r1).sign
    val dc = (c2 - c1).sign
    var r = r1 + dr
    var c = c1 + dc
    while (r != r2 || c != c2) {
        if (board[r * 8 + c] != ' ') return false
        r += dr
        c += dc
    }
    return true
}

fun isSquareAttacked(board: String, targetRow: Int, targetCol: Int, byWhite: Boolean): Boolean {
    for (idx in 0..63) {
        val p = board[idx]
        if (p == ' ' || p.isUpperCase() != byWhite) continue
        val r = idx / 8
        val c = idx % 8
        val dr = targetRow - r
        val dc = targetCol - c
        val adr = abs(dr)
        val adc = abs(dc)

        val attacks = when (p.uppercaseChar()) {
            'P' -> {
                val dir = if (byWhite) -1 else 1
                dr == dir && adc == 1
            }
            'N' -> (adr == 2 && adc == 1) || (adr == 1 && adc == 2)
            'B' -> adr == adc && adr > 0 && isPathClear(board, r, c, targetRow, targetCol)
            'R' -> (adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r, c, targetRow, targetCol)
            'Q' -> (adr == adc || adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r, c, targetRow, targetCol)
            'K' -> adr <= 1 && adc <= 1 && (adr + adc > 0)
            else -> false
        }
        if (attacks) return true
    }
    return false
}

fun isKingInCheck(board: String, whiteKing: Boolean): Boolean {
    val kingChar = if (whiteKing) 'K' else 'k'
    val kingIdx = board.indexOf(kingChar)
    if (kingIdx < 0) return false
    return isSquareAttacked(board, kingIdx / 8, kingIdx % 8, byWhite = !whiteKing)
}

fun canPieceReachGeometrically(board: String, from: Int, to: Int): Boolean {
    if (from == to) return false
    val p = board[from]
    if (p == ' ') return false
    val r1 = from / 8; val c1 = from % 8
    val r2 = to / 8; val c2 = to % 8
    val dr = r2 - r1; val dc = c2 - c1
    val adr = abs(dr); val adc = abs(dc)
    return when (p.uppercaseChar()) {
        'N' -> (adr == 2 && adc == 1) || (adr == 1 && adc == 2)
        'B' -> adr == adc && adr > 0 && isPathClear(board, r1, c1, r2, c2)
        'R' -> (adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r1, c1, r2, c2)
        'Q' -> (adr == adc || adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r1, c1, r2, c2)
        'K' -> adr <= 1 && adc <= 1 && (adr + adc > 0)
        else -> false
    }
}

fun tryMakeLegalMove(
    board: String,
    prevBoard: String?,
    from: Int,
    to: Int,
    whiteToMove: Boolean,
    promoOverride: Char? = null
): Pair<String, String>? {
    if (from !in 0..63 || to !in 0..63 || from == to) return null
    val movingPiece = board[from]
    if (movingPiece == ' ') return null
    if (movingPiece.isUpperCase() != whiteToMove) return null

    val targetPiece = board[to]
    if (targetPiece != ' ' && targetPiece.isUpperCase() == whiteToMove) return null
    if (targetPiece.uppercaseChar() == 'K') return null

    val r1 = from / 8; val c1 = from % 8
    val r2 = to / 8; val c2 = to % 8
    val dr = r2 - r1; val dc = c2 - c1
    val adr = abs(dr); val adc = abs(dc)
    val pieceUpper = movingPiece.uppercaseChar()

    val chars = board.toCharArray()
    var castlingSan: String? = null
    var isEnPassantCapture = false

    when (pieceUpper) {
        'P' -> {
            val dir = if (whiteToMove) -1 else 1
            val startRank = if (whiteToMove) 6 else 1
            val isNormalStep = (dc == 0 && dr == dir && targetPiece == ' ')
            val isDoubleStep = (dc == 0 && r1 == startRank && dr == 2 * dir &&
                    targetPiece == ' ' && board[(r1 + dir) * 8 + c1] == ' ')
            val isDiagonalCapture = (adc == 1 && dr == dir && targetPiece != ' ')

            if (adc == 1 && dr == dir && targetPiece == ' ' && prevBoard != null) {
                val enemyPawn = if (whiteToMove) 'p' else 'P'
                val epStartRank = if (whiteToMove) 1 else 6
                val epLandRank = if (whiteToMove) 3 else 4
                val enemyStartIdx = epStartRank * 8 + c2
                val enemyLandIdx = epLandRank * 8 + c2
                if (r1 == epLandRank &&
                    board[enemyLandIdx] == enemyPawn &&
                    prevBoard[enemyStartIdx] == enemyPawn &&
                    prevBoard[enemyLandIdx] == ' '
                ) {
                    isEnPassantCapture = true
                }
            }

            if (!isNormalStep && !isDoubleStep && !isDiagonalCapture && !isEnPassantCapture) return null
            if (isEnPassantCapture) {
                chars[r1 * 8 + c2] = ' '
            }
        }
        'N' -> {
            if (!((adr == 2 && adc == 1) || (adr == 1 && adc == 2))) return null
        }
        'B' -> {
            if (!(adr == adc && adr > 0 && isPathClear(board, r1, c1, r2, c2))) return null
        }
        'R' -> {
            if (!((adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r1, c1, r2, c2))) return null
        }
        'Q' -> {
            if (!((adr == adc || adr == 0 || adc == 0) && (adr + adc > 0) && isPathClear(board, r1, c1, r2, c2))) return null
        }
        'K' -> {
            val isNormalKingStep = (adr <= 1 && adc <= 1 && (adr + adc > 0))
            val homeRank = if (whiteToMove) 7 else 0
            val isCastlingAttempt = (r1 == homeRank && c1 == 4 && r2 == homeRank && adc == 2 && targetPiece == ' ')

            if (isCastlingAttempt) {
                if (isKingInCheck(board, whiteToMove)) return null
                val rookChar = if (whiteToMove) 'R' else 'r'
                if (c2 == 6) {
                    if (board[homeRank * 8 + 7] != rookChar) return null
                    if (board[homeRank * 8 + 5] != ' ' || board[homeRank * 8 + 6] != ' ') return null
                    if (isSquareAttacked(board, homeRank, 5, !whiteToMove)) return null
                    if (isSquareAttacked(board, homeRank, 6, !whiteToMove)) return null
                    chars[homeRank * 8 + 7] = ' '
                    chars[homeRank * 8 + 5] = rookChar
                    castlingSan = "O-O"
                } else if (c2 == 2) {
                    if (board[homeRank * 8 + 0] != rookChar) return null
                    if (board[homeRank * 8 + 1] != ' ' || board[homeRank * 8 + 2] != ' ' || board[homeRank * 8 + 3] != ' ') return null
                    if (isSquareAttacked(board, homeRank, 3, !whiteToMove)) return null
                    if (isSquareAttacked(board, homeRank, 2, !whiteToMove)) return null
                    chars[homeRank * 8 + 0] = ' '
                    chars[homeRank * 8 + 3] = rookChar
                    castlingSan = "O-O-O"
                } else {
                    return null
                }
            } else if (!isNormalKingStep) {
                return null
            }
        }
        else -> return null
    }

    val isPromotionMove = (pieceUpper == 'P' && (r2 == 0 || r2 == 7))
    val finalPiece = when {
        isPromotionMove && promoOverride != null ->
            if (whiteToMove) promoOverride.uppercaseChar() else promoOverride.lowercaseChar()
        movingPiece == 'P' && r2 == 0 -> 'Q'
        movingPiece == 'p' && r2 == 7 -> 'q'
        else -> movingPiece
    }

    chars[to] = finalPiece
    chars[from] = ' '
    val newBoard = String(chars)

    if (isKingInCheck(newBoard, whiteToMove)) return null

    val isCapture = (targetPiece != ' ') || isEnPassantCapture
    val destSquare = indexToSquare(to)
    val baseSan = if (castlingSan != null) {
        castlingSan
    } else if (pieceUpper == 'P') {
        val filePrefix = if (isCapture) "${'a' + c1}x" else ""
        val promoSuffix = if (isPromotionMove) "=${finalPiece.uppercaseChar()}" else ""
        "$filePrefix$destSquare$promoSuffix"
    } else {
        val otherAmbiguous = (0..63).filter { idx ->
            idx != from && board[idx] == movingPiece && canPieceReachGeometrically(board, idx, to) && run {
                val testChars = board.toCharArray()
                testChars[to] = movingPiece
                testChars[idx] = ' '
                !isKingInCheck(String(testChars), whiteToMove)
            }
        }
        val disambig = when {
            otherAmbiguous.isEmpty() -> ""
            otherAmbiguous.none { (it % 8) == c1 } -> "${'a' + c1}"
            otherAmbiguous.none { (it / 8) == r1 } -> "${8 - r1}"
            else -> indexToSquare(from)
        }
        val capStr = if (isCapture) "x" else ""
        "$pieceUpper$disambig$capStr$destSquare"
    }

    val givesCheck = isKingInCheck(newBoard, !whiteToMove)
    val checkSuffix = if (givesCheck) "+" else ""
    return newBoard to "$baseSan$checkSuffix"
}

fun uciPvToSan(startBoard: String, startPly: Int, uciMoves: List<String>): String {
    var curBoard = startBoard
    var prevBoard: String? = null
    var curPly = startPly
    val outTokens = mutableListOf<String>()

    for ((idx, uci) in uciMoves.withIndex()) {
        val clean = uci.trim()
        if (clean.length < 4) break
        val c1 = clean[0] - 'a'
        val r1 = 8 - (clean[1] - '0')
        val c2 = clean[2] - 'a'
        val r2 = 8 - (clean[3] - '0')
        if (c1 !in 0..7 || r1 !in 0..7 || c2 !in 0..7 || r2 !in 0..7) break

        val fromIdx = r1 * 8 + c1
        val toIdx = r2 * 8 + c2
        val promo = if (clean.length >= 5) clean[4].uppercaseChar() else null
        val whiteTurn = (curPly % 2 == 0)

        val legal = tryMakeLegalMove(curBoard, prevBoard, fromIdx, toIdx, whiteTurn, promo)
        val moveNum = (curPly / 2) + 1
        val numPrefix = when {
            whiteTurn -> "$moveNum."
            idx == 0 -> "$moveNum..."
            else -> ""
        }

        if (legal != null) {
            val (nextBoard, sanStr) = legal
            outTokens.add(if (numPrefix.isNotEmpty()) "$numPrefix$sanStr" else sanStr)
            prevBoard = curBoard
            curBoard = nextBoard
            curPly++
        } else {
            outTokens.add(clean)
            break
        }
    }
    return outTokens.joinToString(" ")
}

// --- PGN GAME METADATA & MULTI-GAME SPLITTER ---
data class PgnGameEntry(
    val title: String,
    val headers: Map<String, String>,
    val rawPgn: String
)

fun splitMultiGamePgn(fullPgnText: String): List<PgnGameEntry> {
    val normalized = fullPgnText.replace("\r\n", "\n")
    val lines = normalized.lines()
    val rawGames = mutableListOf<String>()
    val currentBuilder = StringBuilder()
    var seenMoveLines = false

    for (line in lines) {
        val trimmed = line.trim()
        val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.contains("\"")
        if (isHeader && seenMoveLines) {
            if (currentBuilder.isNotBlank()) {
                rawGames.add(currentBuilder.toString().trim())
            }
            currentBuilder.clear()
            seenMoveLines = false
        }
        if (!isHeader && trimmed.isNotEmpty()) {
            seenMoveLines = true
        }
        currentBuilder.appendLine(line)
    }
    if (currentBuilder.isNotBlank()) {
        rawGames.add(currentBuilder.toString().trim())
    }

    val headerRegex = Regex("^\\[([A-Za-z0-9_]+)\\s+\"(.*)\"\\]$")
    return rawGames.mapIndexedNotNull { idx, gameStr ->
        val headers = mutableMapOf<String, String>()
        for (l in gameStr.lines()) {
            val m = headerRegex.find(l.trim())
            if (m != null) {
                headers[m.groupValues[1]] = m.groupValues[2]
            }
        }
        val white = headers["White"] ?: "White"
        val black = headers["Black"] ?: "Black"
        val event = headers["Event"] ?: ""
        val round = headers["Round"] ?: ""
        val result = headers["Result"] ?: ""
        val title = buildString {
            append("${idx + 1}. $white vs $black")
            if (result.isNotBlank() && result != "*") append(" ($result)")
            if (event.isNotBlank() && event != "?") append(" • $event")
            if (round.isNotBlank() && round != "?") append(" [Rd $round]")
        }
        if (gameStr.isBlank()) null else PgnGameEntry(title, headers, gameStr)
    }
}

fun fenToBoardAndTurn(fen: String): Pair<String, Int>? {
    val parts = fen.trim().split(Regex("\\s+"))
    if (parts.isEmpty()) return null
    val ranks = parts[0].split("/")
    if (ranks.size != 8) return null
    val sb = StringBuilder()
    for (r in ranks) {
        for (ch in r) {
            if (ch.isDigit()) {
                sb.append(" ".repeat(ch - '0'))
            } else {
                sb.append(ch)
            }
        }
    }
    if (sb.length != 64) return null
    val whiteToMove = if (parts.size >= 2) parts[1].lowercase() != "b" else true
    return sb.toString() to (if (whiteToMove) 0 else 1)
}

val CommonChessSymbols = listOf(
    "!", "?", "!?", "?!", "!!", "??",
    "+/=", "+/-", "+-", "=", "∞", "=/∞", "=/+", "-/+", "-+"
)

fun applyMoveTokenToBoard(
    board: String,
    prevBoard: String?,
    rawToken: String,
    whiteToMove: Boolean
): Triple<String, String, String>? {
    var s = rawToken.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O")
    var extractedNag = ""
    for (sym in CommonChessSymbols.sortedByDescending { it.length }) {
        if (s.endsWith(sym)) {
            extractedNag = sym
            s = s.removeSuffix(sym).trim()
            break
        }
    }
    while (s.isNotEmpty() && s.last() in "+#!?") {
        if (extractedNag.isEmpty() && s.last() in "!?") extractedNag = s.last().toString()
        s = s.dropLast(1)
    }
    if (s.isEmpty()) return null

    if (s == "O-O" || s == "O-O-O") {
        val rank = if (whiteToMove) 7 else 0
        val fromIdx = rank * 8 + 4
        val toIdx = if (s == "O-O") rank * 8 + 6 else rank * 8 + 2
        val res = tryMakeLegalMove(board, prevBoard, fromIdx, toIdx, whiteToMove)
        if (res != null) return Triple(res.first, res.second, extractedNag)
    }

    val coordRegex = Regex("([a-h][1-8])[-x]?([a-h][1-8])(?:=([QRBNqrbn]))?")
    val coordMatch = coordRegex.find(s)
    if (coordMatch != null) {
        val fromSq = coordMatch.groupValues[1]
        val toSq = coordMatch.groupValues[2]
        val promo = coordMatch.groupValues[3].firstOrNull()
        val fromIdx = (8 - (fromSq[1] - '0')) * 8 + (fromSq[0] - 'a')
        val toIdx = (8 - (toSq[1] - '0')) * 8 + (toSq[0] - 'a')
        val res = tryMakeLegalMove(board, prevBoard, fromIdx, toIdx, whiteToMove, promo)
        if (res != null) return Triple(res.first, res.second, extractedNag)
    }

    var clean = s
    for (u in listOf("♔", "♕", "♖", "♗", "♘", "♙", "♚", "♛", "♜", "♝", "♞", "♟", "\uFE0E")) {
        clean = clean.replace(u, "")
    }
    var promoPiece: Char? = null
    if (clean.contains("=")) {
        promoPiece = clean.substringAfter("=").firstOrNull()?.uppercaseChar()
        clean = clean.substringBefore("=")
    }
    if (clean.length < 2) return null
    val targetStr = clean.takeLast(2)
    val targetCol = targetStr[0] - 'a'
    val targetRow = 8 - (targetStr[1] - '0')
    if (targetCol !in 0..7 || targetRow !in 0..7) return null
    val toIdx = targetRow * 8 + targetCol

    var prefix = clean.dropLast(2).replace("x", "")
    val pieceUpper = if (prefix.isNotEmpty() && prefix[0] in "KQRBN") {
        val p = prefix[0]
        prefix = prefix.drop(1)
        p
    } else 'P'

    var disambigCol: Int? = null
    var disambigRow: Int? = null
    for (ch in prefix) {
        if (ch in 'a'..'h') disambigCol = ch - 'a'
        else if (ch in '1'..'8') disambigRow = 8 - (ch - '0')
    }

    val desiredChar = if (whiteToMove) pieceUpper else pieceUpper.lowercaseChar()

    for (idx in 0..63) {
        if (board[idx] == desiredChar &&
            (disambigCol == null || idx % 8 == disambigCol) &&
            (disambigRow == null || idx / 8 == disambigRow)
        ) {
            val res = tryMakeLegalMove(board, prevBoard, idx, toIdx, whiteToMove, promoPiece)
            if (res != null) {
                return Triple(res.first, res.second, extractedNag)
            }
        }
    }
    return null
}

data class PgnParseResult(
    val nodes: Map<Int, MoveNode>,
    val nextId: Int,
    val lastMainNodeId: Int,
    val headers: Map<String, String>
)

fun tokenizePgnBody(body: String): List<String> {
    val tokens = mutableListOf<String>()
    var i = 0
    val n = body.length
    while (i < n) {
        val c = body[i]
        when {
            c.isWhitespace() -> i++
            c == '{' -> {
                val start = i
                i++
                while (i < n && body[i] != '}') i++
                if (i < n) i++
                tokens.add(body.substring(start, i))
            }
            c == '(' || c == ')' -> {
                tokens.add(c.toString())
                i++
            }
            c == ';' -> {
                while (i < n && body[i] != '\n') i++
            }
            else -> {
                val start = i
                while (i < n && !body[i].isWhitespace() && body[i] !in "{}()") i++
                tokens.add(body.substring(start, i))
            }
        }
    }
    return tokens
}

fun parsePgnToTree(pgnText: String): PgnParseResult {
    val headers = mutableMapOf<String, String>()
    val headerRegex = Regex("^\\[([A-Za-z0-9_]+)\\s+\"(.*)\"\\]$")
    val bodyLines = mutableListOf<String>()

    for (line in pgnText.lines()) {
        val t = line.trim()
        val m = headerRegex.find(t)
        if (m != null) {
            headers[m.groupValues[1]] = m.groupValues[2]
        } else {
            bodyLines.add(line)
        }
    }

    val startBoardAndTurn = headers["FEN"]?.let { fenToBoardAndTurn(it) }
    val rootBoard = startBoardAndTurn?.first ?: initialBoardString()
    val rootPly = startBoardAndTurn?.second ?: 0

    val tokens = tokenizePgnBody(bodyLines.joinToString("\n"))

    val nodes = mutableMapOf(0 to MoveNode(0, null, "Start", rootBoard, rootPly))
    var nextId = 1
    var currentId = 0
    val branchStack = mutableListOf<Int>()

    for (tok in tokens) {
        when {
            tok == "(" -> {
                val currentNode = nodes[currentId]
                val branchPoint = currentNode?.parentId ?: currentId
                branchStack.add(currentId)
                currentId = branchPoint
            }
            tok == ")" -> {
                if (branchStack.isNotEmpty()) {
                    currentId = branchStack.removeAt(branchStack.lastIndex)
                }
            }
            tok.startsWith("{") && tok.endsWith("}") -> {
                val note = tok.removeSurrounding("{", "}").trim()
                val cur = nodes[currentId]
                if (cur != null && note.isNotEmpty()) {
                    val combined = if (cur.comment.isEmpty()) note else "${cur.comment} $note"
                    nodes[currentId] = cur.copy(comment = combined)
                }
            }
            tok in CommonChessSymbols -> {
                val cur = nodes[currentId]
                if (cur != null) {
                    nodes[currentId] = cur.copy(nag = tok)
                }
            }
            tok in listOf("1-0", "0-1", "1/2-1/2", "½-½", "*") -> {}
            tok.startsWith("$") -> {}
            tok.matches(Regex("\\d+\\.+")) -> {}
            else -> {
                val movePart = tok.replace(Regex("^\\d+\\.+"), "")
                if (movePart.isNotEmpty()) {
                    val parentNode = nodes[currentId] ?: continue
                    val grandParentBoard = parentNode.parentId?.let { nodes[it]?.board }
                    val whiteToMove = (parentNode.ply % 2 == 0)
                    val applied = applyMoveTokenToBoard(parentNode.board, grandParentBoard, movePart, whiteToMove)
                    if (applied != null) {
                        val (newBoard, displaySan, parsedNag) = applied
                        val existingId = parentNode.children.find { cid ->
                            nodes[cid]?.board == newBoard && nodes[cid]?.san == displaySan
                        }
                        if (existingId != null) {
                            currentId = existingId
                        } else {
                            val newId = nextId++
                            val newNode = MoveNode(
                                id = newId,
                                parentId = parentNode.id,
                                san = displaySan,
                                board = newBoard,
                                ply = parentNode.ply + 1,
                                nag = parsedNag
                            )
                            nodes[parentNode.id] = parentNode.copy(children = parentNode.children + newId)
                            nodes[newId] = newNode
                            currentId = newId
                        }
                    }
                }
            }
        }
    }

    val firstMainId = nodes[0]?.children?.firstOrNull() ?: 0
    return PgnParseResult(nodes, nextId, firstMainId, headers)
}

fun buildPgnAnnotatedString(
    nodes: Map<Int, MoveNode>,
    currentNodeId: Int
): AnnotatedString = buildAnnotatedString {
    val root = nodes[0] ?: return@buildAnnotatedString
    if (root.comment.isNotBlank()) {
        withStyle(SpanStyle(color = Color(0xFF2E5A27), fontStyle = FontStyle.Italic)) {
            append("{${root.comment}} ")
        }
    }
    if (root.children.isEmpty()) return@buildAnnotatedString

    fun renderLine(nodeId: Int, forceMoveNumber: Boolean, isVariation: Boolean) {
        val node = nodes[nodeId] ?: return
        val moveNum = (node.ply + 1) / 2
        val isWhiteMove = node.ply % 2 == 1
        val prefix = when {
            isWhiteMove -> "$moveNum. "
            forceMoveNumber -> "$moveNum... "
            else -> ""
        }

        val isSelected = (nodeId == currentNodeId)
        val nagSuffix = if (node.nag.isNotEmpty()) " ${node.nag}" else ""

        pushStringAnnotation(tag = "NODE", annotation = nodeId.toString())
        withStyle(
            SpanStyle(
                color = when {
                    isSelected -> Color(0xFFFFF8F0)
                    isVariation -> Color(0xFF6F4E37)
                    else -> DeepEspressoText
                },
                background = if (isSelected) WarmBrownButton else Color.Transparent,
                fontWeight = if (isSelected || !isVariation) FontWeight.Bold else FontWeight.Normal
            )
        ) {
            append(" $prefix${node.san}$nagSuffix ")
        }
        pop()

        if (node.comment.isNotBlank()) {
            pushStringAnnotation(tag = "NODE", annotation = nodeId.toString())
            withStyle(
                SpanStyle(
                    color = Color(0xFF2E5A27),
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal
                )
            ) {
                append("{${node.comment}} ")
            }
            pop()
        }

        val parent = node.parentId?.let { nodes[it] }
        var hadVariations = false
        if (parent != null && parent.children.firstOrNull() == nodeId && parent.children.size > 1) {
            for (altId in parent.children.drop(1)) {
                hadVariations = true
                withStyle(SpanStyle(color = Color(0xFF6F4E37), fontWeight = FontWeight.Normal)) {
                    append(" (")
                }
                renderLine(altId, forceMoveNumber = true, isVariation = true)
                withStyle(SpanStyle(color = Color(0xFF6F4E37), fontWeight = FontWeight.Normal)) {
                    append(") ")
                }
            }
        }

        val nextMainId = node.children.firstOrNull()
        if (nextMainId != null) {
            renderLine(nextMainId, forceMoveNumber = hadVariations || node.comment.isNotBlank(), isVariation = isVariation)
        }
    }

    val firstId = root.children.first()
    renderLine(firstId, forceMoveNumber = true, isVariation = false)
}

fun getDisplayFileName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: ""
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: name
            }
        }
    }
    return name.lowercase()
}

fun pieceToUnicode(piece: Char): String = when (piece) {
    'K' -> "♔"; 'Q' -> "♕"; 'R' -> "♖"; 'B' -> "♗"; 'N' -> "♘"; 'P' -> "♙"
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟\uFE0E"
    else -> ""
}

fun pieceToSolidGlyph(piece: Char): String = when (piece.lowercaseChar()) {
    'k' -> "♚\uFE0E"
    'q' -> "♛\uFE0E"
    'r' -> "♜\uFE0E"
    'b' -> "♝\uFE0E"
    'n' -> "♞\uFE0E"
    'p' -> "♟\uFE0E"
    else -> ""
}

@Composable
fun SolidChessPiece(
    piece: Char,
    pieceFontSize: androidx.compose.ui.unit.TextUnit = 24.sp
) {
    if (piece == ' ') return
    val glyph = pieceToSolidGlyph(piece)
    val isWhite = piece.isUpperCase()

    if (isWhite) {
        Box(contentAlignment = Alignment.Center) {
            val outlineColor = Color(0xFF221307)
            val offsets = listOf(
                Pair(-1.2.dp, 0.dp), Pair(1.2.dp, 0.dp),
                Pair(0.dp, -1.2.dp), Pair(0.dp, 1.2.dp),
                Pair(-1.dp, -1.dp), Pair(1.dp, -1.dp),
                Pair(-1.dp, 1.dp), Pair(1.dp, 1.dp)
            )
            for ((dx, dy) in offsets) {
                Text(
                    text = glyph,
                    fontSize = pieceFontSize,
                    color = outlineColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(x = dx, y = dy)
                )
            }
            Text(
                text = glyph,
                fontSize = pieceFontSize,
                color = Color(0xFFFFFFFF),
                textAlign = TextAlign.Center
            )
        }
    } else {
        Text(
            text = glyph,
            fontSize = pieceFontSize,
            color = Color(0xFF180E07),
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0x99FFF8F0),
                    offset = Offset(0f, 0f),
                    blurRadius = 3f
                )
            )
        )
    }
}

fun indexToSquare(index: Int): String {
    val file = 'a' + (index % 8)
    val rank = 8 - (index / 8)
    return "$file$rank"
}

fun boardToFen(boardStr: String, whiteToMove: Boolean): String {
    val rows = (0..7).map { r ->
        var empty = 0
        val sb = StringBuilder()
        for (c in 0..7) {
            val p = boardStr[r * 8 + c]
            if (p == ' ') empty++ else {
                if (empty > 0) { sb.append(empty); empty = 0 }
                sb.append(p)
            }
        }
        if (empty > 0) sb.append(empty)
        sb.toString()
    }
    return rows.joinToString("/") + if (whiteToMove) " w - - 0 1" else " b - - 0 1"
}

fun extractEpubChapters(context: Context, uri: Uri): List<String> {
    val chapters = mutableListOf<Pair<String, String>>()
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                        val rawHtml = zis.bufferedReader().readText()
                        val plainText = Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                        if (plainText.length > 40) {
                            chapters.add(name to plainText)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    } catch (e: Exception) {
        return listOf("Error reading EPUB: ${e.message}")
    }
    return chapters.sortedBy { it.first }.map { it.second }.ifEmpty {
        listOf("No readable text chapters found in this EPUB.")
    }
}

// --- EXTERNAL & LOCAL STOCKFISH BINARY LOCATOR ---
// Searches for:
// 1. User-imported Stockfish binary inside context.filesDir
// 2. Externally installed Stockfish / DroidFish / OEX Chess Engine apps via PackageManager
// 3. Embedded libstockfish.so in this app's nativeLibraryDir (if present)
fun locateStockfishBinary(context: Context): Pair<File, String>? {
    // 1. Check user-imported binary in internal filesDir
    val customImported = File(context.filesDir, "stockfish_local")
    if (customImported.exists() && customImported.length() > 10_000) {
        customImported.setExecutable(true)
        return customImported to "Local Imported Stockfish"
    }

    // 2. Check externally installed Stockfish / OEX engine packages on the device
    val pm = context.packageManager
    val candidatePackages = mutableSetOf(
        "org.stockfish",
        "com.stockfish",
        "org.petero.droidfish",
        "com.kalab.chess.engines",
        "com.chess.engine.stockfish"
    )

    // Discover any app advertising the standard Android Chess Engine Provider intent
    runCatching {
        val oexIntents = listOf(
            Intent("intent.chess.provider.ENGINE"),
            Intent("intent.chess.provider.ACTIVATION")
        )
        for (intent in oexIntents) {
            val resolved = pm.queryIntentActivities(intent, 0) + pm.queryIntentServices(intent, 0)
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: info.serviceInfo?.packageName
                if (pkg != null) candidatePackages.add(pkg)
            }
        }
    }

    // Also scan installed packages whose package name contains "stockfish"
    runCatching {
        for (appInfo in pm.getInstalledApplications(0)) {
            if (appInfo.packageName != context.packageName &&
                appInfo.packageName.lowercase().contains("stockfish")
            ) {
                candidatePackages.add(appInfo.packageName)
            }
        }
    }

    for (pkg in candidatePackages) {
        runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val natDir = appInfo.nativeLibraryDir?.let { File(it) }
            if (natDir != null && natDir.exists()) {
                val soFiles = natDir.listFiles().orEmpty()
                val sfBinary = soFiles.firstOrNull {
                    it.name.lowercase().contains("stockfish") && it.canExecute()
                } ?: soFiles.firstOrNull {
                    it.name.endsWith(".so") && !it.name.contains("android") && !it.name.contains("c++")
                }
                if (sfBinary != null && sfBinary.exists()) {
                    return sfBinary to "Installed App ($pkg)"
                }
            }
        }
    }

    // 3. Fallback: Check embedded libstockfish.so inside our own APK (if bundled)
    val embeddedFile = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
    if (embeddedFile.exists()) {
        embeddedFile.setExecutable(true)
        return embeddedFile to "Embedded Stockfish"
    }

    return null
}

fun openExternalUrlOrStore(context: Context, primaryUri: String, fallbackWebUrl: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackWebUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

suspend fun streamStockfishAnalysis(
    context: Context,
    engineFile: File,
    engineLabel: String,
    board: String,
    startPly: Int,
    multiPv: Int = 1,
    maxDepth: Int = 18,
    onUpdate: (String) -> Unit
) = withContext(Dispatchers.IO) {
    engineFile.setExecutable(true)
    val whiteToMove = (startPly % 2 == 0)
    val fen = boardToFen(board, whiteToMove)
    val outputLog = mutableListOf<String>()
    var process: Process? = null
    try {
        process = ProcessBuilder(engineFile.absolutePath)
            .directory(engineFile.parentFile)
            .redirectErrorStream(true)
            .start()

        val writer = process.outputStream.bufferedWriter()
        val reader = process.inputStream.bufferedReader()

        writer.write("uci\n")
        writer.write("setoption name MultiPV value $multiPv\n")
        writer.write("isready\n")
        writer.flush()

        var isReady = false
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            outputLog.add(l)
            if (l.trim() == "readyok") {
                isReady = true
                break
            }
        }

        if (!isReady) {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            val errDetail = outputLog.takeLast(2).joinToString(" | ").ifEmpty { "No output" }
            onUpdate("SF exited early (code $exitCode): $errDetail")
            return@withContext
        }

        writer.write("position fen $fen\n")
        writer.write("go depth $maxDepth\n")
        writer.flush()

        val pvLines = mutableMapOf<Int, String>()
        var currentDepth = 0

        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            outputLog.add(l)

            if (l.startsWith("info ") && l.contains(" score ") && l.contains(" pv ")) {
                val depthMatch = Regex(" depth (\\d+)").find(l)
                val d = depthMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentDepth
                if (d > currentDepth) currentDepth = d

                val mpvMatch = Regex(" multipv (\\d+)").find(l)
                val mpvIndex = mpvMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                var evalScore = ""
                val scoreMatch = Regex("score (cp|mate) (-?\\d+)").find(l)
                if (scoreMatch != null) {
                    val type = scoreMatch.groupValues[1]
                    val raw = scoreMatch.groupValues[2].toIntOrNull() ?: 0
                    val whitePerspectiveRaw = if (whiteToMove) raw else -raw
                    evalScore = if (type == "cp") {
                        val pawns = whitePerspectiveRaw / 100.0
                        if (pawns > 0) "+%.2f".format(pawns) else "%.2f".format(pawns)
                    } else {
                        "M$whitePerspectiveRaw"
                    }
                }
                val rawUciMoves = l.substringAfter(" pv ").trim().split(Regex("\\s+")).take(6)
                val sanLine = uciPvToSan(board, startPly, rawUciMoves)
                pvLines[mpvIndex] = "#$mpvIndex [$evalScore] $sanLine"

                val formattedLines = (1..multiPv).mapNotNull { pvLines[it] }.joinToString("\n")
                onUpdate("⚡ $engineLabel (d$currentDepth/$maxDepth):\n$formattedLines")
            } else if (l.startsWith("bestmove ")) {
                val formattedLines = (1..multiPv).mapNotNull { pvLines[it] }.joinToString("\n")
                onUpdate("✅ $engineLabel (d$maxDepth):\n$formattedLines")
                break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val crashReason = outputLog.takeLast(2).joinToString(" ")
        onUpdate("Engine error: ${e.message} ${if (crashReason.isNotEmpty()) "[$crashReason]" else ""}")
    } finally {
        runCatching {
            process?.outputStream?.write("stop\nquit\n".toByteArray())
            process?.outputStream?.flush()
        }
        process?.destroy()
    }
}

@Composable
fun CompactMetaField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = DeepEspressoText
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(PaperInnerBg, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFFB08968), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = DeepEspressoText
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

object SessionPrefs {
    private const val PREFS_NAME = "chess_study_session"
    private const val KEY_BOOK_URI = "book_uri"
    private const val KEY_BOOK_PAGE = "book_page"
    private const val KEY_BOOK_SCROLL = "book_scroll"
    private const val KEY_SERIALIZED_TREE = "serialized_tree"
    private const val KEY_CURRENT_NODE_ID = "current_node_id"
    private const val KEY_NEXT_NODE_ID = "next_node_id"
    private const val KEY_FULL_PGN_RAW = "full_pgn_raw"
    private const val KEY_ACTIVE_GAME_INDEX = "active_game_index"

    fun persistUriPermission(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun saveBookState(context: Context, uriString: String?, page: Int, scrollOffset: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (uriString != null) putString(KEY_BOOK_URI, uriString)
            putInt(KEY_BOOK_PAGE, page)
            putInt(KEY_BOOK_SCROLL, scrollOffset)
            apply()
        }
    }

    fun saveFullPgnRaw(context: Context, fullPgnRaw: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FULL_PGN_RAW, fullPgnRaw)
            .apply()
    }

    fun saveBoardTreeState(
        context: Context,
        serializedTree: List<String>,
        currentNodeId: Int,
        nextNodeId: Int,
        activeGameIndex: Int,
        fullPgnRaw: String? = null
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_SERIALIZED_TREE, serializedTree.joinToString("\n"))
            putInt(KEY_CURRENT_NODE_ID, currentNodeId)
            putInt(KEY_NEXT_NODE_ID, nextNodeId)
            putInt(KEY_ACTIVE_GAME_INDEX, activeGameIndex)
            if (fullPgnRaw != null) putString(KEY_FULL_PGN_RAW, fullPgnRaw)
            apply()
        }
    }

    fun getBookUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BOOK_URI, null)?.let { Uri.parse(it) }

    fun getBookPage(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_BOOK_PAGE, 0)

    fun getBookScroll(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_BOOK_SCROLL, 0)

    fun getSerializedTree(context: Context): ArrayList<String>? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERIALIZED_TREE, null) ?: return null
        if (raw.isBlank()) return null
        return ArrayList(raw.lines())
    }

    fun getCurrentNodeId(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_CURRENT_NODE_ID, 0)

    fun getNextNodeId(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_NEXT_NODE_ID, 1)

    fun getFullPgnRaw(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FULL_PGN_RAW, null)

    fun getActiveGameIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ACTIVE_GAME_INDEX, 0)
}

@Composable
fun ChessStudyScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val bookScrollState = rememberScrollState()
    val configuration = LocalConfiguration.current

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp >= 600

    var bookText by rememberSaveable {
        mutableStateOf(
            "📖 CHESS STUDY READER\n\n" +
                    "• Tap '📂 Files ▾' above to open a Book (PDF, EPUB, TXT), load a PGN file, save your PGN, or switch games.\n\n" +
                    "⚡ EXTERNAL / LOCAL STOCKFISH SUPPORT:\n" +
                    "• Automatically detects any installed Stockfish app on your phone (or embedded/imported Stockfish).\n" +
                    "• Tap '⚙️ Tools ▾' below the board to turn Stockfish ON, annotate moves, or configure the board!"
        )
    }
    var pdfUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var epubChapters by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalPages by rememberSaveable { mutableIntStateOf(0) }

    var bookZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var pdfPanX by rememberSaveable { mutableFloatStateOf(0f) }
    var pdfPanY by rememberSaveable { mutableFloatStateOf(0f) }

    // --- PGN MOVE TREE STATE ---
    var serializedTree by rememberSaveable {
        mutableStateOf(
            serializeNodes(mapOf(0 to MoveNode(0, null, "Start", initialBoardString(), 0)))
        )
    }
    var currentNodeId by rememberSaveable { mutableIntStateOf(0) }
    var nextNodeId by rememberSaveable { mutableIntStateOf(1) }

    val nodes = remember(serializedTree) { deserializeNodes(serializedTree) }
    val currentNode = nodes[currentNodeId] ?: nodes[0]!!
    val currentBoard = currentNode.board
    val prevBoard = currentNode.parentId?.let { nodes[it]?.board }
    val whiteToMove = (currentNode.ply % 2 == 0)

    var selectedSquare by rememberSaveable { mutableStateOf<Int?>(null) }
    var isBoardFlipped by rememberSaveable { mutableStateOf(false) }
    var showCoordinates by rememberSaveable { mutableStateOf(true) }
    var isEngineRunning by rememberSaveable { mutableStateOf(false) }
    var multiPvCount by rememberSaveable { mutableIntStateOf(3) }
    var engineEvalText by rememberSaveable {
        mutableStateOf("Stockfish OFF • Tap '⚙\uFE0F Tools ▾' for continuous analysis")
    }
    var showNotation by rememberSaveable(isLandscape) { mutableStateOf(!isLandscape) }

    // --- MISSING STOCKFISH INSTALL DIALOG STATE ---
    var showInstallStockfishDialog by rememberSaveable { mutableStateOf(false) }

    // --- PAWN PROMOTION POPUP STATE ---
    var pendingPromoFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingPromoTo by rememberSaveable { mutableStateOf<Int?>(null) }

    // --- CONFIGURE POSITION (SETUP MODE) STATE ---
    var isSetupMode by rememberSaveable { mutableStateOf(false) }
    var setupBoard by rememberSaveable { mutableStateOf(initialBoardString()) }
    var setupSelectedTool by rememberSaveable { mutableStateOf('P') }
    var setupWhiteToMove by rememberSaveable { mutableStateOf(true) }

    // --- PGN GAME METADATA STATE ---
    var metaWhite by rememberSaveable { mutableStateOf("") }
    var metaWhiteElo by rememberSaveable { mutableStateOf("") }
    var metaBlack by rememberSaveable { mutableStateOf("") }
    var metaBlackElo by rememberSaveable { mutableStateOf("") }
    var metaTournament by rememberSaveable { mutableStateOf("") }
    var metaPlace by rememberSaveable { mutableStateOf("") }
    var metaEco by rememberSaveable { mutableStateOf("") }
    var metaRound by rememberSaveable { mutableStateOf("") }
    var metaDate by rememberSaveable { mutableStateOf("2026.11.17") }
    var metaResult by rememberSaveable { mutableStateOf("*") }
    var hasGameMetadataConfigured by rememberSaveable { mutableStateOf(false) }

    // --- MULTI-GAME PGN COLLECTION STATE ---
    var loadedPgnGames by remember {
        mutableStateOf(
            SessionPrefs.getFullPgnRaw(context)
                ?.takeIf { it.isNotBlank() }
                ?.let { splitMultiGamePgn(it) }
                ?: emptyList()
        )
    }
    var activeGameIndex by rememberSaveable { mutableIntStateOf(0) }
    var showGamePickerDialog by rememberSaveable { mutableStateOf(false) }

    // --- UNFOLDING MENUS & TWO-STEP SAVE DIALOGS STATE ---
    var showFilesMenu by remember { mutableStateOf(false) }
    var showBoardMenu by remember { mutableStateOf(false) }
    var showEngineMenu by remember { mutableStateOf(false) }
    var showAnnotationDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveMetadataDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveActionDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPgnSaveContent by rememberSaveable { mutableStateOf("") }

    val pgnAnnotated = remember(nodes, currentNodeId) {
        buildPgnAnnotatedString(nodes, currentNodeId)
    }
    val pgnPlainText = remember(pgnAnnotated) {
        pgnAnnotated.text.trim().replace(Regex("\\s+"), " ")
    }

    val legalTargetsForSelected = remember(selectedSquare, currentBoard, prevBoard, whiteToMove, isSetupMode) {
        val from = selectedSquare
        if (isSetupMode || from == null) emptySet()
        else {
            (0..63).filter { to ->
                tryMakeLegalMove(currentBoard, prevBoard, from, to, whiteToMove) != null
            }.toSet()
        }
    }

    // Launcher allowing the user to pick a downloaded Stockfish binary file from their device if desired
    val importEngineBinaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val destFile = File(context.filesDir, "stockfish_local")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setExecutable(true, false)
                showInstallStockfishDialog = false
                isEngineRunning = true
                showNotation = true
                Toast.makeText(context, "Stockfish binary imported and activated!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to import binary: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun commitLegalMoveToTree(from: Int, to: Int, promoChoice: Char? = null) {
        val legalResult = tryMakeLegalMove(
            board = currentBoard,
            prevBoard = prevBoard,
            from = from,
            to = to,
            whiteToMove = whiteToMove,
            promoOverride = promoChoice
        )
        if (legalResult != null) {
            val (newBoardStr, moveStr) = legalResult
            val existingChildId = currentNode.children.find { cid ->
                nodes[cid]?.san == moveStr && nodes[cid]?.board == newBoardStr
            }

            if (existingChildId != null) {
                currentNodeId = existingChildId
            } else {
                val newId = nextNodeId
                nextNodeId++
                val newNode = MoveNode(
                    id = newId,
                    parentId = currentNode.id,
                    san = moveStr,
                    board = newBoardStr,
                    ply = currentNode.ply + 1
                )
                val updatedParent = currentNode.copy(
                    children = currentNode.children + newId
                )
                val newMap = nodes.toMutableMap()
                newMap[currentNode.id] = updatedParent
                newMap[newId] = newNode
                serializedTree = serializeNodes(newMap)
                currentNodeId = newId
            }
        } else {
            Toast.makeText(context, "Illegal move", Toast.LENGTH_SHORT).show()
        }
        selectedSquare = null
    }

    fun buildFormattedSingleGamePgn(): String {
        val stdResult = if (metaResult == "½-½") "1/2-1/2" else metaResult.ifBlank { "*" }
        val rootNode = nodes[0]
        val isCustomStart = rootNode != null && (rootNode.board != initialBoardString() || rootNode.ply != 0)
        return buildString {
            appendLine("[Event \"${metaTournament.ifBlank { "?" }}\"]")
            appendLine("[Site \"${metaPlace.ifBlank { "?" }}\"]")
            appendLine("[Date \"${metaDate.ifBlank { "????.??.??" }}\"]")
            appendLine("[Round \"${metaRound.ifBlank { "?" }}\"]")
            appendLine("[White \"${metaWhite.ifBlank { "?" }}\"]")
            appendLine("[Black \"${metaBlack.ifBlank { "?" }}\"]")
            appendLine("[Result \"$stdResult\"]")
            if (metaWhiteElo.isNotBlank()) appendLine("[WhiteElo \"$metaWhiteElo\"]")
            if (metaBlackElo.isNotBlank()) appendLine("[BlackElo \"$metaBlackElo\"]")
            if (metaEco.isNotBlank()) appendLine("[ECO \"$metaEco\"]")
            if (isCustomStart && rootNode != null) {
                appendLine("[SetUp \"1\"]")
                appendLine("[FEN \"${boardToFen(rootNode.board, rootNode.ply % 2 == 0)}\"]")
            }
            appendLine()
            if (pgnPlainText.isNotBlank()) {
                append("$pgnPlainText $stdResult")
            } else {
                append(stdResult)
            }
            appendLine()
        }
    }

    fun buildCurrentGameEntry(displayOrderNumber: Int): PgnGameEntry {
        val raw = buildFormattedSingleGamePgn()
        val w = metaWhite.ifBlank { "White" }
        val b = metaBlack.ifBlank { "Black" }
        val ev = metaTournament.takeIf { it.isNotBlank() && it != "?" }
        val rd = metaRound.takeIf { it.isNotBlank() && it != "?" }
        val title = buildString {
            append("$displayOrderNumber. $w vs $b ($metaResult)")
            if (ev != null) append(" • $ev")
            if (rd != null) append(" [Rd $rd]")
        }
        val headers = mapOf(
            "Event" to metaTournament,
            "Site" to metaPlace,
            "Date" to metaDate,
            "Round" to metaRound,
            "White" to metaWhite,
            "WhiteElo" to metaWhiteElo,
            "Black" to metaBlack,
            "BlackElo" to metaBlackElo,
            "ECO" to metaEco,
            "Result" to metaResult
        )
        return PgnGameEntry(title, headers, raw)
    }

    fun ensureCurrentMetadataLoadedAndSynced(): List<PgnGameEntry> {
        val existingEntry = loadedPgnGames.getOrNull(activeGameIndex)
        if (existingEntry != null) {
            val h = existingEntry.headers
            if (metaWhite.isBlank()) metaWhite = h["White"]?.takeIf { it != "?" } ?: ""
            if (metaWhiteElo.isBlank()) metaWhiteElo = h["WhiteElo"] ?: ""
            if (metaBlack.isBlank()) metaBlack = h["Black"]?.takeIf { it != "?" } ?: ""
            if (metaBlackElo.isBlank()) metaBlackElo = h["BlackElo"] ?: ""
            if (metaTournament.isBlank()) metaTournament = h["Event"]?.takeIf { it != "?" } ?: ""
            if (metaPlace.isBlank()) metaPlace = h["Site"]?.takeIf { it != "?" } ?: ""
            if (metaEco.isBlank()) metaEco = h["ECO"] ?: ""
            if (metaRound.isBlank()) metaRound = h["Round"]?.takeIf { it != "?" } ?: ""
            if (metaDate.isBlank()) metaDate = h["Date"]?.takeIf { it != "????.??.??" } ?: "2026.11.17"
        }

        val updatedList = loadedPgnGames.toMutableList()
        if (updatedList.isEmpty()) {
            updatedList.add(buildCurrentGameEntry(1))
            activeGameIndex = 0
        } else {
            val safeIdx = activeGameIndex.coerceIn(0, updatedList.lastIndex)
            activeGameIndex = safeIdx
            updatedList[safeIdx] = buildCurrentGameEntry(safeIdx + 1)
        }
        loadedPgnGames = updatedList
        val combinedRaw = updatedList.joinToString("\n\n") { it.rawPgn.trim() }
        SessionPrefs.saveFullPgnRaw(context, combinedRaw)
        return updatedList
    }

    fun applySingleGameEntryToBoard(entry: PgnGameEntry, gameIdx: Int) {
        val parsed = parsePgnToTree(entry.rawPgn)
        serializedTree = serializeNodes(parsed.nodes)
        nextNodeId = parsed.nextId
        currentNodeId = parsed.lastMainNodeId
        selectedSquare = null
        isSetupMode = false
        showNotation = true
        activeGameIndex = gameIdx

        val h = entry.headers + parsed.headers
        metaWhite = h["White"]?.takeIf { it != "?" } ?: ""
        metaWhiteElo = h["WhiteElo"] ?: ""
        metaBlack = h["Black"]?.takeIf { it != "?" } ?: ""
        metaBlackElo = h["BlackElo"] ?: ""
        metaTournament = h["Event"]?.takeIf { it != "?" } ?: ""
        metaPlace = h["Site"]?.takeIf { it != "?" } ?: ""
        metaEco = h["ECO"] ?: ""
        metaRound = h["Round"]?.takeIf { it != "?" } ?: ""
        metaDate = h["Date"]?.takeIf { it != "????.??.??" } ?: "2026.11.17"
        val res = h["Result"] ?: "*"
        metaResult = if (res == "1/2-1/2") "½-½" else res

        hasGameMetadataConfigured = metaWhite.isNotBlank() || metaBlack.isNotBlank() || metaTournament.isNotBlank()
    }

    fun addNewGameToCurrentPgnCollection() {
        val synced = ensureCurrentMetadataLoadedAndSynced().toMutableList()
        val newOrderNum = synced.size + 1
        metaWhite = ""
        metaWhiteElo = ""
        metaBlack = ""
        metaBlackElo = ""
        metaEco = ""
        metaRound = ""
        metaResult = "*"
        hasGameMetadataConfigured = false
        isSetupMode = false
        serializedTree = serializeNodes(
            mapOf(0 to MoveNode(0, null, "Start", initialBoardString(), 0))
        )
        currentNodeId = 0
        nextNodeId = 1
        selectedSquare = null

        val newBlankRaw = "[Event \"${metaTournament.ifBlank { "?" }}\"]\n[Site \"${metaPlace.ifBlank { "?" }}\"]\n[Date \"$metaDate\"]\n[Round \"?\"]\n[White \"?\"]\n[Black \"?\"]\n[Result \"*\"]\n\n*\n"
        val newEntry = PgnGameEntry(
            title = "$newOrderNum. White vs Black (*)",
            headers = mapOf("Event" to metaTournament, "Site" to metaPlace, "Date" to metaDate, "Round" to "", "Result" to "*"),
            rawPgn = newBlankRaw
        )
        synced.add(newEntry)
        loadedPgnGames = synced
        activeGameIndex = synced.lastIndex
        Toast.makeText(
            context,
            "Started new game ($newOrderNum of ${synced.size}) in current PGN!",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun loadPgnStringOntoBoard(rawPgn: String, sourceLabel: String = "PGN") {
        val games = splitMultiGamePgn(rawPgn)
        if (games.isEmpty()) {
            Toast.makeText(context, "No valid PGN found in $sourceLabel", Toast.LENGTH_SHORT).show()
            return
        }
        SessionPrefs.saveFullPgnRaw(context, rawPgn)
        loadedPgnGames = games
        applySingleGameEntryToBoard(games[0], 0)
        if (games.size > 1) {
            showGamePickerDialog = true
            Toast.makeText(
                context,
                "Found ${games.size} games in PGN! Choose which game to open.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "Loaded game onto board!", Toast.LENGTH_SHORT).show()
        }
    }

    fun renderPdfPage(uri: Uri, pageIndex: Int) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    totalPages = renderer.pageCount
                    val safePage = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    currentPage = safePage
                    renderer.openPage(safePage).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 3, page.height * 3, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.rgb(250, 245, 239))
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pdfBitmap = bmp
                    }
                }
            }
        } catch (e: Exception) {
            bookText = "Error reading PDF: ${e.message}"
            pdfBitmap = null
        }
    }

    var activeBookUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var hasRestoredSession by rememberSaveable { mutableStateOf(false) }

    fun openBookFromUri(uri: Uri, targetPage: Int = 0, targetScroll: Int = 0, isRestoring: Boolean = false) {
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
        val displayName = getDisplayFileName(context, uri)
        activeBookUriString = uri.toString()
        if (!isRestoring) {
            bookZoom = 1f
            pdfPanX = 0f
            pdfPanY = 0f
        }
        when {
            mime.contains("pdf") || displayName.endsWith(".pdf") -> {
                epubChapters = emptyList()
                pdfUriString = uri.toString()
                renderPdfPage(uri, targetPage)
            }
            mime.contains("epub") || displayName.endsWith(".epub") -> {
                pdfBitmap = null
                pdfUriString = null
                val chapters = extractEpubChapters(context, uri)
                epubChapters = chapters
                totalPages = chapters.size
                val safePage = targetPage.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                currentPage = safePage
                bookText = chapters.getOrNull(safePage) ?: "Empty EPUB"
                scope.launch { bookScrollState.scrollTo(targetScroll) }
            }
            else -> {
                pdfBitmap = null
                pdfUriString = null
                epubChapters = emptyList()
                totalPages = 0
                val textContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                } ?: ""
                bookText = textContent
                scope.launch { bookScrollState.scrollTo(targetScroll) }

                val looksLikePgn = displayName.endsWith(".pgn") ||
                        mime.contains("pgn") ||
                        Regex("\\b1\\.\\s*([NBRQK]?[a-h]?[1-8]?x?[a-h][1-8]|O-O)").containsMatchIn(textContent)
                if (!isRestoring && looksLikePgn) {
                    loadPgnStringOntoBoard(textContent, displayName.ifEmpty { "file" })
                }
            }
        }
    }

    // 1. Restore last book, page, scroll offset, PGN collection, and exact move on app startup
    LaunchedEffect(Unit) {
        if (!hasRestoredSession) {
            hasRestoredSession = true

            // Restore Book (PDF, EPUB, or TXT)
            SessionPrefs.getBookUri(context)?.let { savedBookUri ->
                runCatching {
                    val savedPage = SessionPrefs.getBookPage(context)
                    val savedScroll = SessionPrefs.getBookScroll(context)
                    openBookFromUri(savedBookUri, savedPage, savedScroll, isRestoring = true)
                }
            }

            // Restore PGN Games & Move Tree Position
            SessionPrefs.getFullPgnRaw(context)?.let { savedRawPgn ->
                if (savedRawPgn.isNotBlank()) {
                    val games = splitMultiGamePgn(savedRawPgn)
                    if (games.isNotEmpty()) {
                        loadedPgnGames = games
                        val savedIdx = SessionPrefs.getActiveGameIndex(context).coerceIn(0, games.lastIndex)
                        applySingleGameEntryToBoard(games[savedIdx], savedIdx)
                    }
                }
            }

            SessionPrefs.getSerializedTree(context)?.let { savedTree ->
                val parsedNodes = deserializeNodes(savedTree)
                if (parsedNodes.isNotEmpty()) {
                    serializedTree = savedTree
                    val savedNodeId = SessionPrefs.getCurrentNodeId(context)
                    currentNodeId = if (parsedNodes.containsKey(savedNodeId)) savedNodeId else 0
                    nextNodeId = SessionPrefs.getNextNodeId(context)
                }
            }
        }
    }

    // 2. Auto-save book page & scroll position whenever they change
    LaunchedEffect(activeBookUriString, currentPage, bookScrollState.value) {
        if (hasRestoredSession && activeBookUriString != null) {
            SessionPrefs.saveBookState(context, activeBookUriString, currentPage, bookScrollState.value)
        }
    }

    // 3. Auto-save PGN tree & current move position whenever a move is played or navigated
    LaunchedEffect(serializedTree, currentNodeId, nextNodeId, activeGameIndex) {
        if (hasRestoredSession) {
            if (loadedPgnGames.isNotEmpty()) {
                val safeIdx = activeGameIndex.coerceIn(0, loadedPgnGames.lastIndex)
                val updatedList = loadedPgnGames.toMutableList()
                updatedList[safeIdx] = buildCurrentGameEntry(safeIdx + 1)
                loadedPgnGames = updatedList
                val combinedRaw = updatedList.joinToString("\n\n") { it.rawPgn.trim() }
                SessionPrefs.saveFullPgnRaw(context, combinedRaw)
            }
            SessionPrefs.saveBoardTreeState(
                context = context,
                serializedTree = serializedTree,
                currentNodeId = currentNodeId,
                nextNodeId = nextNodeId,
                activeGameIndex = activeGameIndex
            )
        }
    }

    LaunchedEffect(pdfUriString, currentPage) {
        if (pdfUriString != null && pdfBitmap == null) {
            renderPdfPage(Uri.parse(pdfUriString), currentPage)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SessionPrefs.persistUriPermission(context, uri)
            openBookFromUri(uri, targetPage = 0, targetScroll = 0, isRestoring = false)
            SessionPrefs.saveBookState(context, uri.toString(), 0, 0)
        }
    }

    val pgnFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SessionPrefs.persistUriPermission(context, uri)
            val textContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: ""
            if (textContent.isNotBlank()) {
                if (pdfBitmap == null && epubChapters.isEmpty()) {
                    bookText = textContent
                }
                loadPgnStringOntoBoard(textContent, getDisplayFileName(context, uri))
            }
        }
    }

    val savePgnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-chess-pgn")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(pendingPgnSaveContent)
                }
                Toast.makeText(context, "Saved .pgn file successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error saving PGN: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val activeBoardForEngine = if (isSetupMode) setupBoard else currentBoard
    val activePlyForEngine = if (isSetupMode) (if (setupWhiteToMove) 0 else 1) else currentNode.ply

    LaunchedEffect(isEngineRunning, activeBoardForEngine, activePlyForEngine, multiPvCount) {
        if (isEngineRunning) {
            val located = locateStockfishBinary(context)
            if (located == null) {
                isEngineRunning = false
                showInstallStockfishDialog = true
                engineEvalText = "⚠️ Stockfish not installed • Tap '⚙\uFE0F Tools ▾' to install"
            } else {
                val (engineFile, engineLabel) = located
                engineEvalText = "⚡ $engineLabel analyzing top $multiPvCount lines..."
                streamStockfishAnalysis(
                    context = context,
                    engineFile = engineFile,
                    engineLabel = engineLabel,
                    board = activeBoardForEngine,
                    startPly = activePlyForEngine,
                    multiPv = multiPvCount,
                    maxDepth = 18
                ) { liveUpdate ->
                    engineEvalText = liveUpdate
                }
            }
        } else {
            engineEvalText = "Stockfish OFF • Tap '⚙\uFE0F Tools ▾' (PV: ${multiPvCount}x)"
        }
    }

    val tanButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = SoftTanButton,
        contentColor = DeepEspressoText
    )

    // --- DIALOG: INSTALL STOCKFISH PROMPT (F-DROID / GOOGLE PLAY / LOCAL BINARY) ---
    if (showInstallStockfishDialog) {
        AlertDialog(
            onDismissRequest = { showInstallStockfishDialog = false },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    "Install Stockfish Engine",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "No local Stockfish engine was found on your device. Install a Stockfish engine app from F-Droid or Google Play (or select a downloaded Stockfish binary) to enable live analysis:",
                        fontSize = 12.sp,
                        color = DeepEspressoText
                    )

                    // 1. Open F-Droid (DroidFish / Stockfish FOSS)
                    Button(
                        onClick = {
                            showInstallStockfishDialog = false
                            openExternalUrlOrStore(
                                context = context,
                                primaryUri = "fdroid.app://details?id=org.petero.droidfish",
                                fallbackWebUrl = "https://f-droid.org/packages/org.petero.droidfish/"
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E5A27)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📦 Install via F-Droid (FOSS)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // 2. Open Google Play Store search for Stockfish Engine
                    Button(
                        onClick = {
                            showInstallStockfishDialog = false
                            openExternalUrlOrStore(
                                context = context,
                                primaryUri = "market://search?q=Stockfish%20Chess%20Engine",
                                fallbackWebUrl = "https://play.google.com/store/search?q=Stockfish%20Chess%20Engine&c=apps"
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarmBrownButton),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▶️ Find Stockfish on Google Play", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // 3. Official Stockfish GitHub Releases + Local Binary Picker
                    OutlinedButton(
                        onClick = {
                            openExternalUrlOrStore(
                                context = context,
                                primaryUri = "https://github.com/official-stockfish/Stockfish/releases",
                                fallbackWebUrl = "https://stockfishchess.org/download/"
                            )
                        },
                        colors = tanButtonColors,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🌐 Download Official Stockfish Binary", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            importEngineBinaryLauncher.launch(arrayOf("*/*"))
                        },
                        colors = tanButtonColors,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📂 Select Downloaded Stockfish File...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInstallStockfishDialog = false }) {
                    Text("Close", color = DeepEspressoText)
                }
            }
        )
    }

    // --- DIALOG 0: PAWN PROMOTION PIECE-PICKER POPUP ---
    if (pendingPromoFrom != null && pendingPromoTo != null) {
        val fromSq = pendingPromoFrom!!
        val toSq = pendingPromoTo!!
        val promoOptions = listOf(
            'Q' to "Queen",
            'R' to "Rook",
            'B' to "Bishop",
            'N' to "Knight"
        )
        AlertDialog(
            onDismissRequest = {
                pendingPromoFrom = null
                pendingPromoTo = null
                selectedSquare = null
            },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    "Promote Pawn To:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((pieceUpper, label) in promoOptions) {
                        val displayChar = if (whiteToMove) pieceUpper else pieceUpper.lowercaseChar()
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    pendingPromoFrom = null
                                    pendingPromoTo = null
                                    commitLegalMoveToTree(fromSq, toSq, pieceUpper)
                                },
                            colors = CardDefaults.cardColors(containerColor = SoftTanButton),
                            border = BorderStroke(1.5.dp, DarkWalnutBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SolidChessPiece(displayChar)
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepEspressoText
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingPromoFrom = null
                        pendingPromoTo = null
                        selectedSquare = null
                    }
                ) {
                    Text("Cancel", color = DeepEspressoText)
                }
            }
        )
    }

    // --- DIALOG 1: MULTI-GAME PGN COLLECTION SWITCHER ---
    if (showGamePickerDialog) {
        val currentList = loadedPgnGames
        AlertDialog(
            onDismissRequest = { showGamePickerDialog = false },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    "Games in Current PGN (${currentList.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            addNewGameToCurrentPgnCollection()
                            showGamePickerDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B6E33)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("➕ Add New Game to This PGN", color = Color.White, fontSize = 12.sp)
                    }

                    currentList.forEachIndexed { idx, gameEntry ->
                        val isCurrent = (idx == activeGameIndex)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ensureCurrentMetadataLoadedAndSynced()
                                    val refreshed = loadedPgnGames[idx]
                                    applySingleGameEntryToBoard(refreshed, idx)
                                    showGamePickerDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) SoftTanButton else PaperInnerBg
                            ),
                            border = BorderStroke(1.dp, Color(0xFFB08968))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = gameEntry.title + if (isCurrent) " ✓" else "",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = DeepEspressoText
                                    )
                                    val dateStr = gameEntry.headers["Date"] ?: ""
                                    val ecoStr = gameEntry.headers["ECO"] ?: ""
                                    if (dateStr.isNotBlank() || ecoStr.isNotBlank()) {
                                        Text(
                                            text = listOfNotNull(
                                                dateStr.takeIf { it.isNotBlank() },
                                                ecoStr.takeIf { it.isNotBlank() }?.let { "ECO: $it" }
                                            ).joinToString(" • "),
                                            fontSize = 10.sp,
                                            color = Color(0xFF5C3A21)
                                        )
                                    }
                                }

                                if (currentList.size > 1) {
                                    TextButton(
                                        onClick = {
                                            val mutable = currentList.toMutableList()
                                            mutable.removeAt(idx)
                                            loadedPgnGames = mutable
                                            val newIdx = activeGameIndex.coerceIn(0, mutable.lastIndex)
                                            applySingleGameEntryToBoard(mutable[newIdx], newIdx)
                                        }
                                    ) {
                                        Text("🗑️", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGamePickerDialog = false }) {
                    Text("Close", color = WarmBrownButton, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // --- DIALOG 2: NOTATION EDITOR ---
    if (showAnnotationDialog) {
        var draftNag by remember(currentNode.id) { mutableStateOf(currentNode.nag) }
        var draftComment by remember(currentNode.id) { mutableStateOf(currentNode.comment) }

        AlertDialog(
            onDismissRequest = { showAnnotationDialog = false },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    text = if (currentNode.id == 0) "Game Introduction Note"
                    else "Annotate Move: ${currentNode.san}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (currentNode.id != 0) {
                        Text(
                            "Symbols (! ? !? ?! !! ?? +/= +/- +- = ∞ =/∞ =/+ -/+ -+):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepEspressoText
                        )
                        val symbolRows = CommonChessSymbols.chunked(5)
                        for (rowSyms in symbolRows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (sym in rowSyms) {
                                    val selected = (draftNag == sym)
                                    Button(
                                        onClick = { draftNag = if (selected) "" else sym },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) WarmBrownButton else SoftTanButton,
                                            contentColor = if (selected) Color.White else DeepEspressoText
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minWidth = 1.dp, minHeight = 30.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                                    ) {
                                        Text(sym, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        "Written Notes (saved inside { ... } brackets):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepEspressoText
                    )
                    OutlinedTextField(
                        value = draftComment,
                        onValueChange = { draftComment = it },
                        placeholder = { Text("Write your note here...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = currentNode.copy(
                            nag = draftNag,
                            comment = draftComment.replace("{", "(").replace("}", ")").trim()
                        )
                        val newMap = nodes.toMutableMap()
                        newMap[currentNode.id] = updated
                        serializedTree = serializeNodes(newMap)
                        showAnnotationDialog = false
                        showNotation = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmBrownButton)
                ) {
                    Text("Save Note", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAnnotationDialog = false }) {
                    Text("Cancel", color = DeepEspressoText, fontSize = 12.sp)
                }
            }
        )
    }

    // --- DIALOG 3A (POPUP 1): SCROLL-FREE METADATA ENTRY ---
    if (showSaveMetadataDialog) {
        AlertDialog(
            onDismissRequest = { showSaveMetadataDialog = false },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    "Game Metadata (Game #${activeGameIndex + 1})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetaField(
                            label = "White Player",
                            value = metaWhite,
                            onValueChange = { metaWhite = it },
                            modifier = Modifier.weight(0.68f)
                        )
                        CompactMetaField(
                            label = "White Elo",
                            value = metaWhiteElo,
                            onValueChange = { metaWhiteElo = it },
                            modifier = Modifier.weight(0.32f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetaField(
                            label = "Black Player",
                            value = metaBlack,
                            onValueChange = { metaBlack = it },
                            modifier = Modifier.weight(0.68f)
                        )
                        CompactMetaField(
                            label = "Black Elo",
                            value = metaBlackElo,
                            onValueChange = { metaBlackElo = it },
                            modifier = Modifier.weight(0.32f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetaField(
                            label = "Tournament",
                            value = metaTournament,
                            onValueChange = { metaTournament = it },
                            modifier = Modifier.weight(0.55f)
                        )
                        CompactMetaField(
                            label = "Place / Site",
                            value = metaPlace,
                            onValueChange = { metaPlace = it },
                            modifier = Modifier.weight(0.45f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetaField(
                            label = "ECO Code",
                            value = metaEco,
                            onValueChange = { metaEco = it },
                            modifier = Modifier.weight(0.30f)
                        )
                        CompactMetaField(
                            label = "Round",
                            value = metaRound,
                            onValueChange = { metaRound = it },
                            modifier = Modifier.weight(0.30f)
                        )
                        CompactMetaField(
                            label = "Date",
                            value = metaDate,
                            onValueChange = { metaDate = it },
                            modifier = Modifier.weight(0.40f)
                        )
                    }

                    Column {
                        Text(
                            "Result (Who wins):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepEspressoText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("1-0", "0-1", "½-½", "*").forEach { resOpt ->
                                val isSelected = (metaResult == resOpt)
                                Button(
                                    onClick = { metaResult = resOpt },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) WarmBrownButton else SoftTanButton,
                                        contentColor = if (isSelected) Color.White else DeepEspressoText
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                                ) {
                                    Text(resOpt, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        hasGameMetadataConfigured = true
                        ensureCurrentMetadataLoadedAndSynced()
                        showSaveMetadataDialog = false
                        showSaveActionDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmBrownButton)
                ) {
                    Text("Next: Save Options ➔", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveMetadataDialog = false }) {
                    Text("Cancel", color = DeepEspressoText, fontSize = 12.sp)
                }
            }
        )
    }

    // --- DIALOG 3B (POPUP 2): SAVE OPTIONS (With Current Metadata Summary & Edit Button) ---
    if (showSaveActionDialog) {
        val totalGamesCount = loadedPgnGames.size.coerceAtLeast(1)
        val summaryWhite = metaWhite.ifBlank { "?" } + if (metaWhiteElo.isNotBlank()) " ($metaWhiteElo)" else ""
        val summaryBlack = metaBlack.ifBlank { "?" } + if (metaBlackElo.isNotBlank()) " ($metaBlackElo)" else ""
        val summaryTourney = listOfNotNull(
            metaTournament.takeIf { it.isNotBlank() && it != "?" },
            metaPlace.takeIf { it.isNotBlank() && it != "?" },
            metaRound.takeIf { it.isNotBlank() && it != "?" }?.let { "Rd $it" },
            metaEco.takeIf { it.isNotBlank() }?.let { "ECO $it" },
            metaDate.takeIf { it.isNotBlank() && it != "????.??.??" }
        ).joinToString(" • ")

        AlertDialog(
            onDismissRequest = { showSaveActionDialog = false },
            containerColor = ParchmentCardBg,
            title = {
                Text(
                    "Save PGN Options",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DeepEspressoText
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = PaperInnerBg),
                        border = BorderStroke(1.dp, Color(0xFFB08968))
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "⬜ $summaryWhite  vs  ⬛ $summaryBlack  [$metaResult]",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepEspressoText
                            )
                            if (summaryTourney.isNotEmpty()) {
                                Text(
                                    text = summaryTourney,
                                    fontSize = 10.sp,
                                    color = Color(0xFF5C3A21)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    showSaveActionDialog = false
                                    showSaveMetadataDialog = true
                                },
                                colors = tanButtonColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("✏️ Edit Game Metadata (Players, Tournament, Result...)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            addNewGameToCurrentPgnCollection()
                            showSaveActionDialog = false
                        },
                        colors = tanButtonColors,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "➕ Keep This Game & Start Next Game in Same PGN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = {
                            val synced = ensureCurrentMetadataLoadedAndSynced()
                            val allGamesCombined = synced.joinToString("\n\n") { it.rawPgn.trim() } + "\n"
                            pendingPgnSaveContent = allGamesCombined
                            clipboard.setText(AnnotatedString(allGamesCombined))
                            showSaveActionDialog = false
                            val safeFileName = if (metaTournament.isNotBlank() && metaTournament != "?") {
                                "${metaTournament.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.pgn"
                            } else "chess_collection.pgn"
                            savePgnLauncher.launch(safeFileName)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B6E33)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "💾 Save All $totalGamesCount Game${if (totalGamesCount > 1) "s" else ""} in One .pgn File",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            ensureCurrentMetadataLoadedAndSynced()
                            val singlePgn = buildFormattedSingleGamePgn()
                            pendingPgnSaveContent = singlePgn
                            clipboard.setText(AnnotatedString(singlePgn))
                            showSaveActionDialog = false
                            val safeFileName = if (metaWhite.isNotBlank() || metaBlack.isNotBlank()) {
                                "${metaWhite.ifBlank { "White" }}_vs_${metaBlack.ifBlank { "Black" }}.pgn"
                                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            } else "chess_study.pgn"
                            savePgnLauncher.launch(safeFileName)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarmBrownButton),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "📄 Save Only Current Game (.pgn)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSaveActionDialog = false }) {
                    Text("Close", color = DeepEspressoText, fontSize = 12.sp)
                }
            }
        )
    }

    // --- BOOK PANE (Single Top-Left Menu + Inline Page & Zoom Bar) ---
    val bookPane: @Composable (Modifier) -> Unit = { mod ->
        Card(
            modifier = mod,
            colors = CardDefaults.cardColors(containerColor = ParchmentCardBg),
            border = BorderStroke(1.dp, Color(0xFFB08968))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Upper-Left Single Unfolding Menu for Book, Load PGN, Save PGN, and Game Switcher (1/N)
                    Box {
                        val totalCount = loadedPgnGames.size.coerceAtLeast(1)
                        Button(
                            onClick = { showFilesMenu = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WarmBrownButton,
                                contentColor = Color(0xFFFFF8F0)
                            ),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "📂 Files ($totalCount) ▾",
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        DropdownMenu(
                            expanded = showFilesMenu,
                            onDismissRequest = { showFilesMenu = false },
                            modifier = Modifier.background(ParchmentCardBg)
                        ) {
                            DropdownMenuItem(
                                text = { Text("📖 Open Book (PDF / EPUB / TXT)", fontWeight = FontWeight.Bold, color = DeepEspressoText) },
                                onClick = {
                                    showFilesMenu = false
                                    fileLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/epub+zip",
                                            "text/*",
                                            "application/x-chess-pgn",
                                            "application/octet-stream"
                                        )
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("♟️ Load PGN File", fontWeight = FontWeight.Bold, color = Color(0xFF5C3A21)) },
                                onClick = {
                                    showFilesMenu = false
                                    pgnFileLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("💾 Save PGN", fontWeight = FontWeight.Bold, color = Color(0xFF2E5A27)) },
                                onClick = {
                                    showFilesMenu = false
                                    ensureCurrentMetadataLoadedAndSynced()
                                    val alreadyHasMetadata = hasGameMetadataConfigured ||
                                            metaWhite.isNotBlank() ||
                                            metaBlack.isNotBlank() ||
                                            metaTournament.isNotBlank()
                                    if (alreadyHasMetadata) {
                                        showSaveActionDialog = true
                                    } else {
                                        showSaveMetadataDialog = true
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "📑 Games in PGN (${activeGameIndex + 1}/$totalCount)",
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEspressoText
                                    )
                                },
                                onClick = {
                                    showFilesMenu = false
                                    ensureCurrentMetadataLoadedAndSynced()
                                    showGamePickerDialog = true
                                }
                            )
                        }
                    }

                    // Center: Inline Page Navigation (Only shown when a multi-page book is open)
                    if (totalPages > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (currentPage > 0) {
                                        pdfPanX = 0f; pdfPanY = 0f
                                        if (pdfUriString != null) {
                                            renderPdfPage(Uri.parse(pdfUriString), currentPage - 1)
                                        } else if (epubChapters.isNotEmpty()) {
                                            currentPage--
                                            bookText = epubChapters[currentPage]
                                            scope.launch { bookScrollState.scrollTo(0) }
                                        }
                                    }
                                },
                                colors = tanButtonColors,
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("◀", fontSize = 10.sp, maxLines = 1, softWrap = false) }

                            Text(
                                text = "${currentPage + 1}/$totalPages",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepEspressoText,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )

                            OutlinedButton(
                                onClick = {
                                    if (currentPage < totalPages - 1) {
                                        pdfPanX = 0f; pdfPanY = 0f
                                        if (pdfUriString != null) {
                                            renderPdfPage(Uri.parse(pdfUriString), currentPage + 1)
                                        } else if (epubChapters.isNotEmpty()) {
                                            currentPage++
                                            bookText = epubChapters[currentPage]
                                            scope.launch { bookScrollState.scrollTo(0) }
                                        }
                                    }
                                },
                                colors = tanButtonColors,
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("▶", fontSize = 10.sp, maxLines = 1, softWrap = false) }
                        }
                    }

                    // Right: Compact Zoom Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                bookZoom = (bookZoom - 0.25f).coerceAtLeast(1f)
                                if (bookZoom == 1f) { pdfPanX = 0f; pdfPanY = 0f }
                            },
                            colors = tanButtonColors,
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                        ) { Text("🔍-", fontSize = 10.sp, maxLines = 1, softWrap = false) }

                        Text(
                            text = "${(bookZoom * 100).roundToInt()}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepEspressoText,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .clickable {
                                    bookZoom = 1f
                                    pdfPanX = 0f
                                    pdfPanY = 0f
                                }
                        )

                        OutlinedButton(
                            onClick = { bookZoom = (bookZoom + 0.25f).coerceAtMost(4f) },
                            colors = tanButtonColors,
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                        ) { Text("🔍+", fontSize = 10.sp, maxLines = 1, softWrap = false) }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (pdfBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(PaperInnerBg)
                            .border(1.dp, Color(0xFFC8B6A6), RoundedCornerShape(6.dp))
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newZoom = (bookZoom * zoom).coerceIn(1f, 4f)
                                    bookZoom = newZoom
                                    if (newZoom > 1f) {
                                        val maxPanX = (size.width * (newZoom - 1f)) / 2f
                                        val maxPanY = (size.height * (newZoom - 1f)) / 2f
                                        pdfPanX = (pdfPanX + pan.x).coerceIn(-maxPanX, maxPanX)
                                        pdfPanY = (pdfPanY + pan.y).coerceIn(-maxPanY, maxPanY)
                                    } else {
                                        pdfPanX = 0f
                                        pdfPanY = 0f
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = pdfBitmap!!.asImageBitmap(),
                            contentDescription = "Chess Book PDF Page",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                                .graphicsLayer(
                                    scaleX = bookZoom,
                                    scaleY = bookZoom,
                                    translationX = pdfPanX,
                                    translationY = pdfPanY
                                )
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PaperInnerBg, RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFC8B6A6), RoundedCornerShape(6.dp))
                            .verticalScroll(bookScrollState)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = bookText,
                            fontSize = (14f * bookZoom).sp,
                            color = DeepEspressoText,
                            lineHeight = (20f * bookZoom).sp
                        )
                    }
                }
            }
        }
    }

    // --- CHESSBOARD PANE (Responsive Square Board + Single Bottom-Right Tools Menu) ---
    val boardPane: @Composable (Modifier) -> Unit = { mod ->
        val evalScrollState = rememberScrollState()
        val compactBtnMod = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 30.dp)
        val displayedBoard = if (isSetupMode) setupBoard else currentBoard

        Column(
            modifier = mod,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = isSetupMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = ParchmentCardBg),
                    border = BorderStroke(1.dp, DarkWalnutBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tools = listOf('K', 'Q', 'R', 'B', 'N', 'P', 'k', 'q', 'r', 'b', 'n', 'p', ' ')
                            for (tool in tools) {
                                val isSelected = (setupSelectedTool == tool)
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) WoodSelectedSquare else SoftTanButton)
                                        .border(1.dp, DarkWalnutBorder, RoundedCornerShape(6.dp))
                                        .clickable { setupSelectedTool = tool },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (tool == ' ') {
                                        Text("🗑️", fontSize = 13.sp)
                                    } else {
                                        SolidChessPiece(tool, 20.sp)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { setupWhiteToMove = !setupWhiteToMove },
                                colors = tanButtonColors,
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (setupWhiteToMove) "Turn: ⚪ White" else "Turn: ⚫ Black",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            OutlinedButton(
                                onClick = { setupBoard = emptyBoardString() },
                                colors = tanButtonColors,
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Clear", fontSize = 10.sp, maxLines = 1, softWrap = false) }

                            OutlinedButton(
                                onClick = { setupBoard = initialBoardString() },
                                colors = tanButtonColors,
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Initial", fontSize = 10.sp, maxLines = 1, softWrap = false) }

                            Button(
                                onClick = {
                                    val startPly = if (setupWhiteToMove) 0 else 1
                                    serializedTree = serializeNodes(
                                        mapOf(0 to MoveNode(0, null, "Setup", setupBoard, startPly))
                                    )
                                    currentNodeId = 0
                                    nextNodeId = 1
                                    selectedSquare = null
                                    isSetupMode = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B6E33)),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("✅ Apply", fontSize = 10.sp, color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // Outer Wood Panel with BoxWithConstraints so the board ALWAYS renders at max square size
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(WoodPanelBg)
                    .border(3.dp, DarkWalnutBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        showCoordinates = !showCoordinates
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                val boardSide = minOf(maxWidth, maxHeight)
                val dynamicPieceSp = ((boardSide.value / 8f) * 0.62f).coerceIn(12f, 28f).sp
                val dynamicCoordSp = ((boardSide.value / 8f) * 0.22f).coerceIn(6f, 10f).sp

                Box(
                    modifier = Modifier
                        .size(boardSide)
                        .border(2.5.dp, DarkWalnutBorder)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        for (visualRow in 0..7) {
                            val actualRow = if (isBoardFlipped) 7 - visualRow else visualRow
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                for (visualCol in 0..7) {
                                    val actualCol = if (isBoardFlipped) 7 - visualCol else visualCol
                                    val index = actualRow * 8 + actualCol
                                    val isLight = (actualRow + actualCol) % 2 == 0
                                    val isSelected = !isSetupMode && (selectedSquare == index)
                                    val isLegalTarget = !isSetupMode && (index in legalTargetsForSelected)
                                    val bgColor = when {
                                        isSelected -> WoodSelectedSquare
                                        isLight -> WoodLightSquare
                                        else -> WoodDarkSquare
                                    }
                                    val coordColor = if (isLight) Color(0xFF7A4B24) else Color(0xFFF5E6D3)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(bgColor)
                                            .clickable {
                                                if (isSetupMode) {
                                                    val chars = setupBoard.toCharArray()
                                                    chars[index] = if (chars[index] == setupSelectedTool) ' ' else setupSelectedTool
                                                    setupBoard = String(chars)
                                                } else {
                                                    val clickedPiece = currentBoard[index]
                                                    val isOwnPiece = clickedPiece != ' ' && (clickedPiece.isUpperCase() == whiteToMove)

                                                    if (selectedSquare == null) {
                                                        if (isOwnPiece) {
                                                            selectedSquare = index
                                                        } else if (clickedPiece != ' ') {
                                                            val turnName = if (whiteToMove) "White" else "Black"
                                                            Toast.makeText(context, "It is $turnName's turn to move", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        val from = selectedSquare!!
                                                        if (from == index) {
                                                            selectedSquare = null
                                                        } else if (isOwnPiece) {
                                                            selectedSquare = index
                                                        } else {
                                                            val movingPiece = currentBoard[from]
                                                            val isPromoRank = (movingPiece == 'P' && actualRow == 0) ||
                                                                    (movingPiece == 'p' && actualRow == 7)
                                                            val legalTest = tryMakeLegalMove(
                                                                board = currentBoard,
                                                                prevBoard = prevBoard,
                                                                from = from,
                                                                to = index,
                                                                whiteToMove = whiteToMove
                                                            )

                                                            if (legalTest != null && isPromoRank) {
                                                                pendingPromoFrom = from
                                                                pendingPromoTo = index
                                                            } else {
                                                                commitLegalMoveToTree(from, index, null)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                    ) {
                                        if (showCoordinates && visualCol == 0) {
                                            Text(
                                                text = "${8 - actualRow}",
                                                fontSize = dynamicCoordSp,
                                                fontWeight = FontWeight.Bold,
                                                color = coordColor,
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(start = 1.5.dp, top = 0.5.dp)
                                            )
                                        }
                                        if (showCoordinates && visualRow == 7) {
                                            Text(
                                                text = "${'a' + actualCol}",
                                                fontSize = dynamicCoordSp,
                                                fontWeight = FontWeight.Bold,
                                                color = coordColor,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(end = 1.5.dp, bottom = 0.dp)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            SolidChessPiece(displayedBoard[index], dynamicPieceSp)
                                            if (isLegalTarget) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(if (displayedBoard[index] == ' ') 9.dp else 24.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (displayedBoard[index] == ' ') Color(0x552E5A27)
                                                            else Color(0x44B8332A)
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showNotation) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 64.dp else 80.dp)
                        .padding(top = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = ParchmentCardBg),
                    border = BorderStroke(1.dp, Color(0xFFB08968))
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(evalScrollState)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        val turnIndicator = if (whiteToMove) "⚪ White to move" else "⚫ Black to move"
                        if (pgnAnnotated.isEmpty()) {
                            Text(
                                text = "$turnIndicator • Tap wood side of board to toggle coordinates",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = DeepEspressoText
                            )
                        } else {
                            ClickableText(
                                text = pgnAnnotated,
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                ),
                                onClick = { offset ->
                                    pgnAnnotated.getStringAnnotations(tag = "NODE", start = offset, end = offset)
                                        .firstOrNull()?.let { ann ->
                                            val targetId = ann.item.toIntOrNull()
                                            if (targetId != null && nodes.containsKey(targetId)) {
                                                currentNodeId = targetId
                                                selectedSquare = null
                                            }
                                        }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = engineEvalText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp,
                            color = if (isEngineRunning) Color(0xFF2E5A27) else Color(0xFF5C3A21),
                            fontWeight = if (isEngineRunning) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // --- ULTRA-COMPACT BOTTOM BAR: ◀  ▶  ⌫  and Single Unfolding Menu (Board / Annotate / Engine) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        currentNode.parentId?.let { pid ->
                            currentNodeId = pid
                            selectedSquare = null
                        }
                    },
                    enabled = currentNode.parentId != null && !isSetupMode,
                    colors = tanButtonColors,
                    modifier = compactBtnMod,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("◀", fontSize = 12.sp, maxLines = 1, softWrap = false) }

                OutlinedButton(
                    onClick = {
                        currentNode.children.firstOrNull()?.let { cid ->
                            currentNodeId = cid
                            selectedSquare = null
                        }
                    },
                    enabled = currentNode.children.isNotEmpty() && !isSetupMode,
                    colors = tanButtonColors,
                    modifier = compactBtnMod,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("▶", fontSize = 12.sp, maxLines = 1, softWrap = false) }

                OutlinedButton(
                    onClick = {
                        val pid = currentNode.parentId
                        if (pid != null) {
                            val toRemove = collectSubtreeIds(currentNode.id, nodes)
                            val newMap = nodes.filterKeys { it !in toRemove }.toMutableMap()
                            val parent = newMap[pid]
                            if (parent != null) {
                                newMap[pid] = parent.copy(children = parent.children - currentNode.id)
                            }
                            serializedTree = serializeNodes(newMap)
                            currentNodeId = pid
                            selectedSquare = null
                        }
                    },
                    enabled = currentNode.parentId != null && !isSetupMode,
                    colors = tanButtonColors,
                    modifier = compactBtnMod,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) { Text("⌫", fontSize = 12.sp, maxLines = 1, softWrap = false) }

                // SINGLE RIGHT-CORNER UNFOLDING BUTTON FOR BOARD, ANNOTATE, AND ENGINE
                Box {
                    val hasNote = currentNode.nag.isNotEmpty() || currentNode.comment.isNotEmpty()
                    Button(
                        onClick = { showBoardMenu = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEngineRunning) Color(0xFF3B6E33) else WarmBrownButton,
                            contentColor = Color(0xFFFFF8F0)
                        ),
                        modifier = compactBtnMod,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        val label = buildString {
                            if (isEngineRunning) append("⚡ SF:${multiPvCount}x")
                            else append("⚙️ Tools")
                            if (hasNote) append("*")
                            append(" ▾")
                        }
                        Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }

                    DropdownMenu(
                        expanded = showBoardMenu,
                        onDismissRequest = { showBoardMenu = false },
                        modifier = Modifier.background(ParchmentCardBg)
                    ) {
                        // --- SECTION 1: ANNOTATE & NOTATION ---
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (hasNote) "✍️ Edit Move Note / Symbol (*)" else "✍️ Annotate Move (!, ?, {note})",
                                    fontWeight = FontWeight.Bold,
                                    color = DeepEspressoText
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                showAnnotationDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (showNotation) "📜 Hide Notation Box" else "📜 Show Notation Box",
                                    color = DeepEspressoText
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                showNotation = !showNotation
                            }
                        )

                        HorizontalDivider(color = Color(0xFFC8B6A6))

                        // --- SECTION 2: STOCKFISH ENGINE ---
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isEngineRunning) "⏹️ Turn Stockfish OFF" else "⚡ Turn Stockfish ON (${multiPvCount}x)",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E5A27)
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                if (!isEngineRunning) {
                                    if (locateStockfishBinary(context) == null) {
                                        showInstallStockfishDialog = true
                                    } else {
                                        isEngineRunning = true
                                        showNotation = true
                                    }
                                } else {
                                    isEngineRunning = false
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "🔢 Engine Lines (Currently ${multiPvCount}x) ▸",
                                    color = DeepEspressoText
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                showEngineMenu = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📦 Install / Configure Local Stockfish...", color = DeepEspressoText) },
                            onClick = {
                                showBoardMenu = false
                                showInstallStockfishDialog = true
                            }
                        )

                        HorizontalDivider(color = Color(0xFFC8B6A6))

                        // --- SECTION 3: BOARD & PGN ACTIONS ---
                        DropdownMenuItem(
                            text = { Text("➕ Add new game to current PGN", fontWeight = FontWeight.Bold, color = Color(0xFF2E5A27)) },
                            onClick = {
                                showBoardMenu = false
                                addNewGameToCurrentPgnCollection()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🆕 New board (Reset current game)", color = DeepEspressoText) },
                            onClick = {
                                showBoardMenu = false
                                isSetupMode = false
                                serializedTree = serializeNodes(
                                    mapOf(0 to MoveNode(0, null, "Start", initialBoardString(), 0))
                                )
                                currentNodeId = 0
                                nextNodeId = 1
                                selectedSquare = null
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isSetupMode) "✅ Exit Configure Position" else "🛠️ Configure position",
                                    color = DeepEspressoText
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                if (!isSetupMode) {
                                    setupBoard = currentBoard
                                    setupWhiteToMove = whiteToMove
                                    isSetupMode = true
                                } else {
                                    isSetupMode = false
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("⇅ Rotate board", color = DeepEspressoText) },
                            onClick = {
                                showBoardMenu = false
                                isBoardFlipped = !isBoardFlipped
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (showCoordinates) "🔢 Hide coordinates" else "🔢 Show coordinates",
                                    color = DeepEspressoText
                                )
                            },
                            onClick = {
                                showBoardMenu = false
                                showCoordinates = !showCoordinates
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📋 Paste PGN from Clipboard", color = DeepEspressoText) },
                            onClick = {
                                showBoardMenu = false
                                val clipText = clipboard.getText()?.text ?: ""
                                if (clipText.isNotBlank()) {
                                    loadPgnStringOntoBoard(clipText, "clipboard")
                                } else {
                                    Toast.makeText(context, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    // Secondary sub-menu when choosing number of Engine Lines (1x..5x)
                    DropdownMenu(
                        expanded = showEngineMenu,
                        onDismissRequest = { showEngineMenu = false },
                        modifier = Modifier.background(ParchmentCardBg)
                    ) {
                        for (pv in 1..5) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Show $pv candidate line${if (pv > 1) "s" else ""} (${pv}x)" +
                                                if (multiPvCount == pv) " ✓" else "",
                                        color = DeepEspressoText
                                    )
                                },
                                onClick = {
                                    multiPvCount = pv
                                    showNotation = true
                                    showEngineMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftLightBrownBg)
                .safeDrawingPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            bookPane(Modifier.weight(0.5f).fillMaxHeight())
            boardPane(Modifier.weight(0.5f).fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftLightBrownBg)
                .safeDrawingPadding()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bookPane(Modifier.weight(0.44f).fillMaxWidth())
            boardPane(Modifier.weight(0.56f).fillMaxWidth())
        }
    }
}