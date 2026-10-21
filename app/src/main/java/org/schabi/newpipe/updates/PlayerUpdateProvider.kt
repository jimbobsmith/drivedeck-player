package org.schabi.newpipe.updates

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.core.content.FileProvider

/** A separate component keeps update URI grants narrower than the inherited file picker. */
class PlayerUpdateProvider : FileProvider()

/** Primary updater actions remain easy to tap on tablets and at large font sizes. */
class UpdateActionPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.minimumHeight = (72 * context.resources.displayMetrics.density).toInt()
    }
}
