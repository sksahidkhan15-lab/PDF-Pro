package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.PdfTextSegment

/**
 * Contextual PhoText.ai-inspired bottom editing bar.
 * Appears ONLY when existing text is tapped on the PDF canvas.
 * Typing updates the PDF canvas live; tapping the green ✓ commits the edit.
 */
@Composable
fun BottomTextEditPanel(
  selectedSegment: PdfTextSegment?,
  draftText: String,
  onDraftChanged: (String) -> Unit,
  onConfirm: () -> Unit,
  onRestoreOriginal: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = selectedSegment != null,
    enter = slideInVertically(initialOffsetY = { it }),
    exit = slideOutVertically(targetOffsetY = { it }),
    modifier = modifier,
  ) {
    if (selectedSegment != null) {
      val focusRequester = remember { FocusRequester() }

      LaunchedEffect(selectedSegment.id) {
        try {
          focusRequester.requestFocus()
        } catch (_: Exception) {
        }
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .imePadding()
          .navigationBarsPadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter,
      ) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
            .testTag("bottom_edit_panel"),
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
          shadowElevation = 10.dp,
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // Matched style & background status strip (read-only smart detection info)
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
              ) {
                Box(
                  modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(selectedSegment.style.colorArgb))
                )
                Text(
                  text = selectedSegment.style.summaryLabel(),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                val bgLabel = when {
                  selectedSegment.isOcrDetected &&
                    selectedSegment.background.mode == BackgroundMode.COMPLEX_INTERPOLATED ->
                    "OCR · Edge Interpolated"
                  selectedSegment.isOcrDetected -> "OCR · BG Reconstructed"
                  selectedSegment.background.mode == BackgroundMode.COMPLEX_INTERPOLATED ->
                    "Complex BG Interpolated"
                  selectedSegment.background.mode == BackgroundMode.SOLID_COLOR ->
                    "Color BG Matched"
                  else -> "Clean BG"
                }
                Surface(
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  shape = RoundedCornerShape(6.dp),
                ) {
                  Text(
                    text = bgLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1,
                  )
                }
              }

              Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedSegment.isEdited || draftText != selectedSegment.originalText) {
                  IconButton(
                    onClick = onRestoreOriginal,
                    modifier = Modifier.size(36.dp).testTag("restore_segment_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.Restore,
                      contentDescription = "Restore original text",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(19.dp),
                    )
                  }
                }
                IconButton(
                  onClick = onDismiss,
                  modifier = Modifier.size(36.dp).testTag("dismiss_edit_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close editor",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                  )
                }
              }
            }

            // Input Field + Single ✓ Confirm Button
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              OutlinedTextField(
                value = draftText,
                onValueChange = onDraftChanged,
                singleLine = true,
                placeholder = { Text("Type replacement text...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = MaterialTheme.colorScheme.primary,
                  unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                  focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                  unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                ),
                modifier = Modifier
                  .weight(1f)
                  .focusRequester(focusRequester)
                  .testTag("edit_text_input"),
              )

              FilledIconButton(
                onClick = onConfirm,
                modifier = Modifier
                  .size(54.dp)
                  .testTag("confirm_edit_button"),
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Check,
                  contentDescription = "Confirm text replacement",
                  modifier = Modifier.size(28.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}
