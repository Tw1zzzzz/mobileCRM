package com.pulse.android.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Note
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The shared models keep iOS SF Symbol names (`icon = "moon"`); this maps them to Material icons.
 * Unknown names fall back to a neutral dot icon.
 */
fun sfIcon(name: String): ImageVector = when (name) {
    "applewatch" -> Icons.Outlined.Watch
    "arrow.counterclockwise" -> Icons.Outlined.Undo
    "arrow.right" -> Icons.AutoMirrored.Outlined.ArrowForward
    "arrow.triangle.2.circlepath", "clock.arrow.circlepath" -> Icons.Outlined.Sync
    "arrow.up.forward.circle.fill", "arrow.up.right", "arrow.up.right.circle.fill", "chart.line.uptrend.xyaxis" -> Icons.AutoMirrored.Outlined.TrendingUp
    "chart.line.downtrend.xyaxis" -> Icons.AutoMirrored.Outlined.TrendingDown
    "bed.double" -> Icons.Outlined.Bed
    "bell" -> Icons.Outlined.Notifications
    "bell.slash" -> Icons.Outlined.NotificationsOff
    "bolt" -> Icons.Outlined.Bolt
    "bolt.heart", "waveform.path.ecg", "heart.text.square" -> Icons.Outlined.MonitorHeart
    "book.closed" -> Icons.AutoMirrored.Outlined.MenuBook
    "brain.head.profile" -> Icons.Outlined.Psychology
    "burst" -> Icons.Outlined.Grain
    "calendar" -> Icons.Outlined.CalendarMonth
    "calendar.badge.checkmark" -> Icons.Outlined.EventAvailable
    "calendar.badge.clock", "calendar.badge.exclamationmark" -> Icons.Outlined.EventBusy
    "calendar.badge.plus" -> Icons.Outlined.EditCalendar
    "camera", "camera.viewfinder", "camera.badge.ellipsis" -> Icons.Outlined.CameraAlt
    "chart.bar", "chart.bar.fill" -> Icons.Outlined.BarChart
    "chart.xyaxis.line" -> Icons.AutoMirrored.Outlined.ShowChart
    "checklist" -> Icons.Outlined.Checklist
    "checkmark" -> Icons.Outlined.Check
    "checkmark.seal.fill" -> Icons.Outlined.Verified
    "chevron.left" -> Icons.Outlined.ChevronLeft
    "chevron.right" -> Icons.Outlined.ChevronRight
    "chevron.up" -> Icons.Outlined.ExpandLess
    "chevron.down" -> Icons.Outlined.ExpandMore
    "play.fill" -> Icons.Filled.PlayArrow
    "circle.hexagongrid" -> Icons.Outlined.Hexagon
    "clock" -> Icons.Outlined.AccessTime
    "cloud" -> Icons.Outlined.Cloud
    "cup.and.saucer" -> Icons.Outlined.Coffee
    "delete.left" -> Icons.Outlined.Backspace
    "drop" -> Icons.Outlined.WaterDrop
    "dumbbell" -> Icons.Outlined.FitnessCenter
    "ellipsis" -> Icons.Outlined.MoreHoriz
    "exclamationmark.circle.fill" -> Icons.Outlined.Error
    "exclamationmark.triangle.fill" -> Icons.Outlined.Warning
    "eye" -> Icons.Outlined.Visibility
    "face.smiling" -> Icons.Outlined.Face
    "figure.flexibility", "figure.mind.and.body" -> Icons.Outlined.SelfImprovement
    "figure.run" -> Icons.AutoMirrored.Outlined.DirectionsRun
    "flag" -> Icons.Outlined.Flag
    "flame", "flame.fill" -> Icons.Outlined.LocalFireDepartment
    "fork.knife" -> Icons.Outlined.Restaurant
    "gamecontroller" -> Icons.Outlined.SportsEsports
    "gearshape" -> Icons.Outlined.Settings
    "hand.raised" -> Icons.Outlined.PanTool
    "hand.tap" -> Icons.Outlined.TouchApp
    "heart" -> Icons.Outlined.FavoriteBorder
    "heart.fill" -> Icons.Outlined.Favorite
    "hourglass" -> Icons.Outlined.HourglassEmpty
    "house" -> Icons.Outlined.Home
    "iphone" -> Icons.Outlined.Smartphone
    "leaf" -> Icons.Outlined.Spa
    "lightbulb.fill" -> Icons.Outlined.Lightbulb
    "link" -> Icons.Outlined.Link
    "list.bullet.rectangle" -> Icons.AutoMirrored.Outlined.ListAlt
    "lock", "lock.fill" -> Icons.Outlined.Lock
    "lock.open" -> Icons.Outlined.LockOpen
    "magnifyingglass" -> Icons.Outlined.Search
    "metronome" -> Icons.Outlined.Speed
    "moon", "moon.fill", "moon.zzz" -> Icons.Outlined.Bedtime
    "moon.stars" -> Icons.Outlined.Nightlight
    "note.text" -> Icons.AutoMirrored.Outlined.Note
    "number" -> Icons.Outlined.Tag
    "paperplane.fill" -> Icons.AutoMirrored.Outlined.Send
    "pencil", "square.and.pencil" -> Icons.Outlined.Edit
    "minus" -> Icons.Outlined.Remove
    "person" -> Icons.Outlined.Person
    "person.3", "person.3.fill" -> Icons.Outlined.Groups
    "person.badge.shield.checkmark" -> Icons.Outlined.AdminPanelSettings
    "person.crop.circle.badge.questionmark" -> Icons.Outlined.PersonSearch
    "person.text.rectangle" -> Icons.Outlined.Badge
    "photo.on.rectangle" -> Icons.Outlined.Image
    "play.circle", "play.rectangle", "play.rectangle.fill" -> Icons.Outlined.PlayCircle
    "plus" -> Icons.Outlined.Add
    "point.3.connected.trianglepath.dotted" -> Icons.Outlined.Hub
    "safari" -> Icons.Outlined.Explore
    "scope", "target" -> Icons.Outlined.GpsFixed
    "shield.lefthalf.filled" -> Icons.Outlined.Shield
    "shoe", "shoeprints.fill" -> Icons.Outlined.DirectionsWalk
    "snowflake" -> Icons.Outlined.AcUnit
    "sparkle", "sparkles" -> Icons.Outlined.Lightbulb
    "square.grid.2x2" -> Icons.Outlined.Apps
    "square.stack.3d.up" -> Icons.Outlined.Layers
    "star" -> Icons.Outlined.StarBorder
    "star.fill" -> Icons.Outlined.Star
    "stop.circle" -> Icons.Outlined.StopCircle
    "sun.max" -> Icons.Outlined.WbSunny
    "sunrise" -> Icons.Outlined.WbTwilight
    "textformat" -> Icons.Outlined.FormatColorText
    "trash" -> Icons.Outlined.Delete
    "trophy" -> Icons.Outlined.EmojiEvents
    "xmark", "xmark.circle.fill" -> Icons.Outlined.Close
    "globe" -> Icons.Outlined.Language
    "info" -> Icons.Outlined.Info
    "account" -> Icons.Outlined.AccountCircle
    "camera.add" -> Icons.Outlined.AddAPhoto
    "history" -> Icons.Outlined.History
    "check.circle" -> Icons.Outlined.CheckCircle
    "light" -> Icons.Outlined.LightMode
    else -> Icons.Outlined.QuestionMark
}
