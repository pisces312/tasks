package org.tasks.compose.chips

import androidx.compose.runtime.Composable
import org.tasks.themes.TasksIcons

/**
 * Icon-only "+" rendered next to [SubtaskChip] to create a subtask without opening the task.
 *
 * The description is passed to [Chip] so it lands on the same semantics node as the click action;
 * the icon itself is decorative and does not contribute its own label.
 */
@Composable
fun AddSubtaskChip(
    contentDescription: String,
    onClick: () -> Unit,
) {
    Chip(
        icon = TasksIcons.ADD,
        text = null,
        color = defaultChipColor(),
        onClick = onClick,
        contentDescription = contentDescription,
    )
}
