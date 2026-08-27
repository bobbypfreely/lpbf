package com.bobbypfreely.lpbf.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bobbypfreely.lpbf.waveform.MarkAndCutFragment

class ProjectPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
	override fun getItemCount(): Int = 4

	override fun createFragment(position: Int): Fragment {
		return when (position) {
			0 -> MarkAndCutFragment()
			1 -> FineTuneFragment()
			2 -> PlaceFragment()
			else -> SpliceFragment()
		}
	}
}
