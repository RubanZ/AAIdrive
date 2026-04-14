package me.hufman.androidautoidrive.phoneui

import android.widget.RadioGroup
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.carapp.maps.YandexPuckStyle

/**
 * Two-way DataBinding glue between [me.hufman.androidautoidrive.phoneui.viewmodels.MapSettingsModel.mapPuckStyle]
 * (a `LiveData<String>` carrying a [YandexPuckStyle.storageKey]) and a
 * [RadioGroup] in `fragment_map_settings.xml` whose children correspond to the
 * puck variants. The mapping between `RadioButton` ids and storage keys lives
 * here so the layout XML stays free of magic strings.
 *
 * Why a flavor-local adapter instead of a `BindingAdapter` in main source: the
 * `R.id.puck_*` resources only exist in the yandexmap flavor, and other map
 * flavors don't need this binding at all. Keeping it in
 * `app/src/yandexmap/java/...` means gmap/mapbox/nomap don't link the file.
 */
private val ID_TO_KEY = mapOf(
		R.id.puck_arrow_blue to YandexPuckStyle.ARROW_BLUE.storageKey,
		R.id.puck_arrow_red to YandexPuckStyle.ARROW_RED.storageKey,
)

private val KEY_TO_ID = ID_TO_KEY.entries.associate { (id, key) -> key to id }

@BindingAdapter("puckStyleSelection")
fun bindPuckStyleSelection(group: RadioGroup, value: String?) {
	val targetId = KEY_TO_ID[value] ?: KEY_TO_ID[YandexPuckStyle.DEFAULT.storageKey] ?: return
	if (group.checkedRadioButtonId != targetId) {
		group.check(targetId)
	}
}

@BindingAdapter("puckStyleSelectionAttrChanged")
fun bindPuckStyleSelectionInverseListener(group: RadioGroup, attrChanged: InverseBindingListener?) {
	if (attrChanged == null) {
		group.setOnCheckedChangeListener(null)
	} else {
		group.setOnCheckedChangeListener { _, _ -> attrChanged.onChange() }
	}
}

@InverseBindingAdapter(attribute = "puckStyleSelection")
fun getPuckStyleSelection(group: RadioGroup): String {
	return ID_TO_KEY[group.checkedRadioButtonId] ?: YandexPuckStyle.DEFAULT.storageKey
}
