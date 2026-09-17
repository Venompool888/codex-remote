package app.codexremote.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.codexremote.android.RemoteImageContent
import app.codexremote.android.ui.LocalRemoteImagePresenter
import app.codexremote.android.ui.LocalRemoteImageRepository
import app.codexremote.android.ui.LocalRemoteImageScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.CodeHighlight
import app.codexremote.android.InlineMarkdownNode
import app.codexremote.android.InlineMarkdownParser
import app.codexremote.android.MarkdownBlock
import app.codexremote.android.MarkdownParser
import app.codexremote.android.R
import app.codexremote.android.ui.theme.AppColors

@Composable
fun ComposeMarkdown(
    markdown: String,
    onOpenUrl: (String) -> Unit = {},
    onCopyCode: (String) -> Unit = {},
    onOpenImage: (String, String) -> Unit = { _, _ -> },
    onOpenArtifacts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    SelectionContainer { MarkdownBlocks(
        blocks = blocks,
        onOpenUrl = onOpenUrl,
        onCopyCode = onCopyCode,
        onOpenImage = onOpenImage,
        onOpenArtifacts = onOpenArtifacts,
        modifier = modifier
    ) }
}

@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenImage: (String, String) -> Unit,
    onOpenArtifacts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = AppColors.onSurface
    val linkColor = AppColors.primary
    val codeBgColor = AppColors.inlineCodeBackground

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.sp
                    }
                    val inline = remember(block.text, contentColor, linkColor, codeBgColor) {
                        buildInlineMarkdown(
                            nodes = InlineMarkdownParser.parse(block.text),
                            contentColor = contentColor,
                            linkColor = linkColor,
                            codeBgColor = codeBgColor
                        )
                    }
                    LinkedMarkdownText(
                        text = inline,
                        style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = contentColor, lineHeight = fontSize * 1.35f),
                        onClick = { offset ->
                            inline.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                if (it.item.startsWith("remote-artifact://")) onOpenArtifacts() else onOpenUrl(it.item)
                            }
                        }
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    val inline = remember(block.text, contentColor, linkColor, codeBgColor) {
                        buildInlineMarkdown(
                            nodes = InlineMarkdownParser.parse(block.text),
                            contentColor = contentColor,
                            linkColor = linkColor,
                            codeBgColor = codeBgColor
                        )
                    }
                    LinkedMarkdownText(
                        text = inline,
                        onClick = { offset ->
                            inline.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                if (it.item.startsWith("remote-artifact://")) onOpenArtifacts()
                                else onOpenUrl(it.item)
                            }
                        }
                    )
                }
                is MarkdownBlock.ListBlock -> {
                    val maxMarker = if (block.ordered) "${block.start.toLong() + block.items.lastIndex}." else "•"
                    val markerPixels = rememberTextMeasurer().measure(maxMarker, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)).size.width
                    val markerWidth = with(LocalDensity.current) { markerPixels.toDp() } + 12.dp
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        block.items.forEachIndexed { index, item ->
                            val marker = if (block.ordered) "${block.start.toLong() + index}." else "•"
                            val inline = remember(item, contentColor, linkColor, codeBgColor) {
                                buildInlineMarkdown(
                                    nodes = InlineMarkdownParser.parse(item),
                                    contentColor = contentColor,
                                    linkColor = linkColor,
                                    codeBgColor = codeBgColor
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = marker,
                                    fontWeight = if (block.ordered) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    color = contentColor,
                                    modifier = Modifier.width(markerWidth)
                                )
                                LinkedMarkdownText(
                                    text = inline,
                                    modifier = Modifier.weight(1f),
                                    onClick = { offset ->
                                        inline.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                            if (it.item.startsWith("remote-artifact://")) onOpenArtifacts()
                                            else onOpenUrl(it.item)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Table -> {
                    MarkdownTableComposable(
                        table = block,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        codeBgColor = codeBgColor,
                        onOpenUrl = onOpenUrl,
                        onOpenArtifacts = onOpenArtifacts,
                        onCopy = { onCopyCode(block.source) }
                    )
                }
                is MarkdownBlock.Code -> {
                    MarkdownCodeBlockComposable(
                        code = block.text,
                        language = block.language,
                        onCopy = { onCopyCode(block.text) }
                    )
                }
                is MarkdownBlock.Quote -> {
                    val parsedBlocks = remember(block.text) { MarkdownParser.parse(block.text) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(AppColors.outlineVariant, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MarkdownBlocks(
                                blocks = parsedBlocks,
                                onOpenUrl = onOpenUrl,
                                onCopyCode = onCopyCode,
                                onOpenImage = onOpenImage,
                                onOpenArtifacts = onOpenArtifacts
                            )
                        }
                    }
                }
                is MarkdownBlock.Rule -> {
                    HorizontalDivider(
                        color = AppColors.outlineVariant,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.Image -> {
                    MarkdownImageComposable(
                        image = block,
                        onOpenImage = onOpenImage
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownImageComposable(
    image: MarkdownBlock.Image,
    onOpenImage: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val imageRepo = LocalRemoteImageRepository.current
    val imageScope = LocalRemoteImageScope.current
    val presenter = LocalRemoteImagePresenter.current
    var content by remember(image.source, imageScope) { mutableStateOf<RemoteImageContent?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }

    DisposableEffect(image.source, imageScope, retryTrigger, imageRepo) {
        if (imageRepo == null) return@DisposableEffect onDispose {}
        val cancel = imageRepo.loadImage(image.source, image.alt) { result ->
            content = result
        }
        onDispose { cancel() }
    }

    val shape = RoundedCornerShape(8.dp)
    val bitmap = content?.bitmap

    if (bitmap != null) {
        val longImage = bitmap.height > bitmap.width * 2.2
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AppColors.surfaceContainerLow, shape)
                .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
                .clickable {
                    content?.let { presenter(it, image.alt) }
                    onOpenImage(image.source, image.alt)
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.alt.ifBlank { "Attached image" },
                contentScale = if (longImage) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (longImage) Modifier.height(360.dp) else Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)))
            )
        }
    } else if (content?.error != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AppColors.surfaceContainerLow, shape)
                .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
                .clickable { retryTrigger++ }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning_amber),
                    contentDescription = null,
                    tint = AppColors.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "${content?.error ?: "Image unavailable"} · tap to retry",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.error
                )
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(shape)
                .background(AppColors.surfaceContainerLow, shape)
                .border(BorderStroke(1.dp, AppColors.outlineVariant), shape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = AppColors.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun MarkdownCodeBlockComposable(
    code: String,
    language: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val isDark = isSystemInDarkTheme()

    val highlightedCode = remember(code, language, isDark) {
        val tokens = CodeHighlight.tokens(code, language)
        if (tokens.isEmpty()) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                append(code)
                tokens.forEach { token ->
                    val color = when (token.kind) {
                        CodeHighlight.Kind.STRING -> if (isDark) Color(0xff83d197) else Color(0xff356b24)
                        CodeHighlight.Kind.COMMENT -> if (isDark) Color(0xffd8d8d8) else Color(0xff888888)
                        CodeHighlight.Kind.KEYWORD, CodeHighlight.Kind.OPERATOR -> if (isDark) Color(0xfff8a6c8) else Color(0xff6c3c9e)
                        CodeHighlight.Kind.CALL, CodeHighlight.Kind.IDENTIFIER -> if (isDark) Color(0xffb897f4) else Color(0xff6c3c9e)
                        CodeHighlight.Kind.NUMBER -> if (isDark) Color(0xfff1a275) else Color(0xff965418)
                    }
                    addStyle(SpanStyle(color = color), token.start, token.end)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerHigh, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
    ) {
        Column {
            // Code header: language and copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.onSurfaceMuted
                )

                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onCopy()
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                        contentDescription = "Copy code",
                        tint = if (copied) AppColors.success else AppColors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 11.sp,
                        color = if (copied) AppColors.success else AppColors.onSurfaceVariant
                    )
                }
            }

            // Code content
            SelectionContainer { Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (language.lowercase() in setOf("", "text", "plain", "plaintext")) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(12.dp)
            ) {
                Text(
                    text = highlightedCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = AppColors.onSurface
                )
            } }
        }
    }
}

@Composable
fun MarkdownTableComposable(
    table: MarkdownBlock.Table,
    contentColor: Color,
    linkColor: Color,
    codeBgColor: Color,
    onOpenUrl: (String) -> Unit,
    onOpenArtifacts: () -> Unit = {},
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    val numCols = table.headers.size
    val columnWidths = remember(table) {
        (0 until numCols).map { col ->
            val maxHeaderChars = table.headers.getOrNull(col)?.length ?: 0
            val maxRowChars = table.rows.maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0
            val maxChars = maxOf(maxHeaderChars, maxRowChars)
            (maxChars * 9 + 32).coerceIn(80, 240).dp
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
    ) {
        Column {
            // Header with copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Table (${table.rows.size} rows)",
                    fontSize = 12.sp,
                    color = AppColors.onSurfaceMuted
                )
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onCopy()
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                        contentDescription = "Copy table",
                        tint = if (copied) AppColors.success else AppColors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 11.sp,
                        color = if (copied) AppColors.success else AppColors.onSurfaceVariant
                    )
                }
            }

            // Scrollable table body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Header row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        table.headers.forEachIndexed { col, header ->
                            val align = table.alignment.getOrNull(col) ?: MarkdownBlock.Alignment.LEFT
                            val textAlign = when (align) {
                                MarkdownBlock.Alignment.LEFT -> TextAlign.Start
                                MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                                MarkdownBlock.Alignment.RIGHT -> TextAlign.End
                            }
                            Text(
                                text = header,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                                textAlign = textAlign,
                                modifier = Modifier.width(columnWidths.getOrElse(col) { 100.dp })
                            )
                        }
                    }

                    HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)

                    // Data rows
                    table.rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            row.forEachIndexed { col, cell ->
                                val align = table.alignment.getOrNull(col) ?: MarkdownBlock.Alignment.LEFT
                                val textAlign = when (align) {
                                    MarkdownBlock.Alignment.LEFT -> TextAlign.Start
                                    MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                                    MarkdownBlock.Alignment.RIGHT -> TextAlign.End
                                }
                                val inline = remember(cell, contentColor, linkColor, codeBgColor) {
                                    buildInlineMarkdown(
                                        nodes = InlineMarkdownParser.parse(cell),
                                        contentColor = contentColor,
                                        linkColor = linkColor,
                                        codeBgColor = codeBgColor
                                    )
                                }
                                LinkedMarkdownText(
                                    text = inline,
                                    style = TextStyle(lineHeight = 21.sp, textAlign = textAlign),
                                    modifier = Modifier.width(columnWidths.getOrElse(col) { 100.dp }),
                                    onClick = { offset ->
                                        inline.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                            if (it.item.startsWith("remote-artifact://")) onOpenArtifacts()
                                            else onOpenUrl(it.item)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildInlineMarkdown(
    nodes: List<InlineMarkdownNode>,
    contentColor: Color,
    linkColor: Color,
    codeBgColor: Color
): AnnotatedString = buildAnnotatedString {
    nodes.forEach { node ->
        when (node) {
            is InlineMarkdownNode.Text -> {
                withStyle(SpanStyle(color = contentColor, fontSize = 15.sp)) {
                    append(node.value)
                }
            }
            is InlineMarkdownNode.Strong -> {
                withStyle(SpanStyle(color = contentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)) {
                    append(node.value)
                }
            }
            is InlineMarkdownNode.Code -> {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBgColor,
                    color = contentColor,
                    fontSize = 13.sp
                )) {
                    append(" ${node.value} ")
                }
            }
            is InlineMarkdownNode.Link -> {
                pushStringAnnotation(tag = "URL", annotation = node.url)
                withStyle(SpanStyle(
                    color = linkColor,
                    fontSize = 15.sp,
                    textDecoration = TextDecoration.Underline
                )) {
                    append(node.label)
                }
                pop()
            }
        }
    }
}

@Composable
private fun LinkedMarkdownText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle = TextStyle(lineHeight = 21.sp), onClick: (Int) -> Unit) {
    val linked = buildAnnotatedString {
        append(text)
        text.getStringAnnotations("URL", 0, text.length).forEach { link ->
            addLink(LinkAnnotation.Clickable(link.item, linkInteractionListener = { onClick(link.start) }), link.start, link.end)
        }
    }
    Text(linked, modifier = modifier, style = style)
}
