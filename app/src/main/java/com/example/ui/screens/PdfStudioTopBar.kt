package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PdfStudioTopBar(
  hasDocument: Boolean,
  documentName: String,
  zoomScale: Float,
  canUndo: Boolean,
  canRedo: Boolean,
  editedCount: Int,
  isExporting: Boolean,
  onBackHome: () -> Unit,
  onOpenPdf: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onFitToScreen: () -> Unit,
  onRotatePage: () -> Unit,
  onRestoreOriginal: () -> Unit,
  onExportPdf: () -> Unit,
) {
  var showMoreMenu by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    shadowElevation = 2.dp,
  ) {
    Column(modifier = Modifier.statusBarsPadding()) {
      // Primary Toolbar Row: Logo/Back, Title, Upload, Download
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (hasDocument) {
          IconButton(
            onClick = onBackHome,
            modifier = Modifier.testTag("back_home_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back to projects",
            )
          }
        } else {
          Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Rounded.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
              )
            }
          }
          Spacer(modifier = Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = if (hasDocument) documentName else "PDF Studio Pro",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (hasDocument) {
            Text(
              text = if (editedCount > 0) "$editedCount text replacement(s) active" else "Tap any text to edit",
              style = MaterialTheme.typography.bodyMedium,
              color = if (editedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        IconButton(
          onClick = onOpenPdf,
          modifier = Modifier.testTag("toolbar_open_pdf_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = "Upload PDF",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        if (hasDocument) {
          Button(
            onClick = onExportPdf,
            enabled = !isExporting,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier
              .height(40.dp)
              .testTag("toolbar_export_pdf_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          ) {
            Icon(
              imageVector = Icons.Rounded.Download,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (isExporting) "Saving..." else "Export",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }

      // Secondary Precision Row (only when document is open): Undo, Redo, Zoom, Rotate
      if (hasDocument) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = onUndo,
              enabled = canUndo,
              modifier = Modifier.testTag("undo_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
              )
            }
            IconButton(
              onClick = onRedo,
              enabled = canRedo,
              modifier = Modifier.testTag("redo_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
              )
            }
          }

          // Zoom Control Cluster
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
              )
              .padding(horizontal = 2.dp),
          ) {
            IconButton(
              onClick = onZoomOut,
              modifier = Modifier.size(40.dp).testTag("zoom_out_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = "Zoom out",
                modifier = Modifier.size(18.dp),
              )
            }
            Text(
              text = "${(zoomScale * 100).roundToInt()}%",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onFitToScreen)
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .testTag("zoom_fit_button"),
            )
            IconButton(
              onClick = onZoomIn,
              modifier = Modifier.size(40.dp).testTag("zoom_in_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Zoom in",
                modifier = Modifier.size(18.dp),
              )
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = onRotatePage,
              modifier = Modifier.testTag("rotate_page_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.RotateRight,
                contentDescription = "Rotate page",
              )
            }
            Box {
              IconButton(
                onClick = { showMoreMenu = true },
                modifier = Modifier.testTag("more_actions_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.MoreVert,
                  contentDescription = "More options",
                )
              }
              DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
              ) {
                DropdownMenuItem(
                  text = { Text("Fit to Screen") },
                  onClick = {
                    showMoreMenu = false
                    onFitToScreen()
                  },
                )
                DropdownMenuItem(
                  text = { Text("Restore Original Document") },
                  leadingIcon = {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                  },
                  onClick = {
                    showMoreMenu = false
                    onRestoreOriginal()
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}
