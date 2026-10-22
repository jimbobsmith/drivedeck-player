package org.schabi.newpipe.local.subscription.item

import android.view.View
import android.graphics.Color
import org.schabi.newpipe.local.subscription.ProfileSelection
import com.xwray.groupie.viewbinding.BindableItem
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.databinding.FeedGroupCardItemBinding
import org.schabi.newpipe.local.subscription.FeedGroupIcon

data class FeedGroupCardItem @JvmOverloads constructor(
    val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
    val name: String,
    val icon: FeedGroupIcon,
    val bgColor: String = "#333333",
    val textColor: String = "#FFFFFF"
) : BindableItem<FeedGroupCardItemBinding>() {
    constructor (feedGroupEntity: FeedGroupEntity) : this(feedGroupEntity.uid, feedGroupEntity.name, feedGroupEntity.icon, feedGroupEntity.bgColor, feedGroupEntity.textColor)

    override fun getId(): Long {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> super.getId()
            else -> groupId
        }
    }

    override fun getLayout(): Int = R.layout.feed_group_card_item

    override fun bind(viewBinding: FeedGroupCardItemBinding, position: Int) {
        val selected = ProfileSelection.current(viewBinding.root.context) == groupId
        viewBinding.title.text = if (selected) "✓ $name" else name
        viewBinding.root.isSelected = selected
        viewBinding.root.contentDescription = if (selected) viewBinding.root.context.getString(R.string.profile_selected, name) else name
        viewBinding.icon.setImageResource(icon.getDrawableRes())
        // Every bind resets colors, including recycled cards from another profile.
        val background = runCatching { Color.parseColor(bgColor) }.getOrDefault(Color.DKGRAY)
        val foreground = runCatching { Color.parseColor(textColor) }.getOrDefault(Color.WHITE)
        viewBinding.root.setCardBackgroundColor(background)
        viewBinding.title.setBackgroundColor(background)
        viewBinding.title.setTextColor(foreground)
        viewBinding.icon.setColorFilter(foreground)
    }

    override fun initializeViewBinding(view: View) = FeedGroupCardItemBinding.bind(view)
}
