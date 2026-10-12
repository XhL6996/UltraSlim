package app.ultraslim.ui.adaptive

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class WorkspacePlanTest {
    @Test fun widthAndHeightBothConstrainSideBySideContent() {
        assertFalse(workspacePlan(420f, 900f, 1f, emptyList()).expanded)
        assertTrue(workspacePlan(800f, 900f, 1f, emptyList()).expanded)
        assertFalse(workspacePlan(800f, 200f, 1f, emptyList()).expanded)
        assertFalse(workspacePlan(800f, 900f, 2f, emptyList()).expanded)
    }
    @Test fun narrowerSettingsPanelsFitWithoutShrinkingPhotoWorkspaces() {
        assertFalse(workspacePlan(612f, 780f, 1f, emptyList()).expanded)
        val settings = workspacePlan(612f, 780f, 1f, emptyList(), 280f, 600f)
        assertTrue(settings.expanded)
        assertTrue(settings.primary.width >= 280)
        assertTrue(settings.secondary!!.width >= 280)
        assertFalse(workspacePlan(612f, 780f, 1f, emptyList(), 560f, 600f).expanded)
    }
    @Test fun verticalHingeDoesNotIntersectEitherRegion() {
        val hinge = Rect(480f, 0f, 520f, 1000f)
        val plan = workspacePlan(1000f, 1000f, 1f, listOf(WorkspaceFold(hinge, false)))
        assertTrue(plan.expanded)
        assertTrue(plan.primary.right < hinge.left)
        assertTrue(plan.secondary!!.left > hinge.right)
    }
    @Test fun tabletopKeepsControlsBelowTheFold() {
        val hinge = Rect(0f, 460f, 800f, 480f)
        val plan = workspacePlan(800f, 900f, 1f, listOf(WorkspaceFold(hinge, true)))
        assertTrue(plan.expanded)
        assertTrue(plan.primary.bottom < hinge.top)
        assertTrue(plan.secondary!!.top > hinge.bottom)
    }
    @Test fun smallFoldAreaUsesLargerSafeRegionInsteadOfCrossingHinge() {
        val plan = workspacePlan(600f, 900f, 1f, listOf(WorkspaceFold(Rect(150f, 0f, 170f, 900f), false)))
        assertFalse(plan.expanded)
        assertTrue(plan.primary.left > 170f)
        assertEquals(600f, plan.primary.right)
    }
}
