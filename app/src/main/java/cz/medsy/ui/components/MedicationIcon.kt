package cz.medsy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Soap
import androidx.compose.material.icons.rounded.Vaccines
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.medsy.domain.model.MedicationForm

/**
 * A glyph per dose form, so a row can be recognised before the name is read. The morphing shape
 * stays underneath: the shape carries the form as a silhouette and the icon names it, which is what
 * makes "Healing" readable as a patch and "Soap" as a gel rather than a white blob.
 *
 * Material ships no tablet-versus-capsule pair, so the oral forms share `Medication`; their
 * silhouettes differ and that is the distinction the eye already uses.
 */
val MedicationForm.icon: ImageVector
    get() = when (this) {
        MedicationForm.TABLET -> Icons.Rounded.Medication
        MedicationForm.CAPSULE -> Icons.Rounded.Grain
        MedicationForm.SUBLINGUAL -> Icons.Rounded.Medication
        MedicationForm.INJECTION_IM -> Icons.Rounded.Vaccines
        MedicationForm.INJECTION_SUBQ -> Icons.Rounded.Vaccines
        MedicationForm.PATCH -> Icons.Rounded.Healing
        MedicationForm.GEL -> Icons.Rounded.Soap
        MedicationForm.SPRAY -> Icons.Rounded.Air
        MedicationForm.DROPS -> Icons.Rounded.WaterDrop
        MedicationForm.SUPPLEMENT -> Icons.Rounded.Eco
    }

/**
 * The form's silhouette with its glyph on top.
 *
 * @param container the translucent fill the silhouette is drawn in
 * @param content the glyph tint, which must stay legible on `container` (the callers pass a
 *        `primary`-tinted container, not a solid `primaryContainer`)
 */
@Composable
fun MedicationIcon(
    form: MedicationForm,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
    content: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        FormShape(form = form, size = size, color = container)
        Icon(
            imageVector = form.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * GLYPH_RATIO),
        )
    }
}

/** The glyph sits inside the silhouette, so it must not touch its edge. */
private const val GLYPH_RATIO = 0.56f
