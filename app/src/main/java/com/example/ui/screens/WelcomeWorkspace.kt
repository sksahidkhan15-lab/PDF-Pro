package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.pdf.engine.SamplePdfKind
import com.example.pdf.model.LocalProjectSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun WelcomeWorkspace(
  recentProjects: List<LocalProjectSummary>,
  onOpenPdfClick: () -> Unit,
  onOpenSample: (SamplePdfKind) -> Unit,
  onOpenRecent: (LocalProjectSummary) -> Unit,
  onDeleteRecent: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 760.dp),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        HeroStudioCard(onOpenPdfClick = onOpenPdfClick)
      }

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.sample_documents),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(999.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = "100% On-Device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }

      items(SamplePdfKind.entries) { kind ->
        SamplePdfCard(
          kind = kind,
          onClick = { onOpenSample(kind) },
        )
      }

      if (recentProjects.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = stringResource(R.string.recent_projects),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }

        items(recentProjects, key = { it.projectId }) { project ->
          RecentProjectRow(
            project = project,
            onClick = { onOpenRecent(project) },
            onDelete = { onDeleteRecent(project.projectId) },
          )
        }
      }

      item {
        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun HeroStudioCard(onOpenPdfClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(176.dp)
          .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_pdf_workspace_hero_1795077939459),
          contentDescription = "PDF Studio Pro smart text editing preview",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  androidx.compose.ui.graphics.Color.Transparent,
                  MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ),
                startY = 90f,
              )
            )
        )
        Surface(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(14.dp),
          color = MaterialTheme.colorScheme.primary,
          shape = RoundedCornerShape(999.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.AutoFixHigh,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(15.dp),
            )
            Text(
              text = "One-Tap Smart Replace",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimary,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.displaySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "Tap any existing text on a PDF, type the replacement, watch it update live with matched font & clean background removal, then confirm with ✓.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
          onClick = onOpenPdfClick,
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("open_pdf_hero_button"),
          shape = MaterialTheme.shapes.medium,
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "Upload / Open PDF",
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }
  }
}

@Composable
private fun SamplePdfCard(
  kind: SamplePdfKind,
  onClick: () -> Unit,
) {
  val icon = when (kind) {
    SamplePdfKind.TICKET_PASS -> Icons.Rounded.ConfirmationNumber
    SamplePdfKind.BILINGUAL_DOC -> Icons.Outlined.Translate
    SamplePdfKind.SCANNED_INVOICE -> Icons.Outlined.DocumentScanner
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("sample_${kind.name.lowercase()}")
      .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(
        modifier = Modifier.size(46.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = kind.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = kind.badge,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
        Text(
          text = kind.subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Icon(
        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
        contentDescription = "Open sample",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun RecentProjectRow(
  project: LocalProjectSummary,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val formattedDate = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(project.lastModifiedEpochMs))

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Description,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = project.displayName,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${project.pageCount} page(s) · ${project.editCount} edit(s) · $formattedDate",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = Icons.Outlined.DeleteOutline,
          contentDescription = "Delete local project",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
