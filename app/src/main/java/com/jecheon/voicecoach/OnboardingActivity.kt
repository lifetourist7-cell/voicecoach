package com.jecheon.voicecoach

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 첫 실행 시 보여주는 3-page carousel 온보딩.
 *
 * 페이지 구성:
 * 1. 환영 + 기능 3개 안내
 * 2. 워치 종류 선택 (BLE / Wear OS / 없음)
 * 3. 선택 기반 셋업 가이드 (HelpActivity 의 const 텍스트 재사용)
 *
 * 종료 후 prefs `first_launch_done = true` 저장 → MainActivity 가 다음부터 곧장 진입.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button
    private lateinit var indicators: List<View>

    private val pageCount = 3
    private var selectedDeviceType: String = "ble"

    /** 시스템 폰트 스케일 무시 — 항상 1.0x 로 렌더링. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        applyEdgeToEdge()

        pager = findViewById(R.id.onboardingPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)
        indicators = listOf(
            findViewById(R.id.indicator0),
            findViewById(R.id.indicator1),
            findViewById(R.id.indicator2)
        )

        pager.adapter = PageAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
                updateButtonText(position)
                // 페이지 3 (셋업) 진입 시 현재 selectedDeviceType 반영
                if (position == 2) refreshSetupPage()
            }
        })

        btnNext.setOnClickListener {
            val current = pager.currentItem
            if (current < pageCount - 1) {
                pager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        btnSkip.setOnClickListener {
            finishOnboarding()
        }
    }

    private fun updateIndicators(position: Int) {
        // 일반 <View> 는 wrap_content 가 부모 전체로 확장되는 버그가 있어 명시적 px 로 sizing.
        val density = resources.displayMetrics.density
        val px6 = (6f * density).toInt()
        val pxActive = (24f * density).toInt()  // page_indicator_active 의 width
        indicators.forEachIndexed { i, v ->
            val params = v.layoutParams
            if (i == position) {
                v.setBackgroundResource(R.drawable.page_indicator_active)
                params.width = pxActive
                params.height = px6
            } else {
                v.setBackgroundResource(R.drawable.page_indicator_inactive)
                params.width = px6
                params.height = px6
            }
            v.layoutParams = params
        }
    }

    private fun updateButtonText(position: Int) {
        btnNext.text = if (position == pageCount - 1) "시작하기" else "다음"
        btnSkip.visibility = if (position == pageCount - 1) View.INVISIBLE else View.VISIBLE
    }

    private fun finishOnboarding() {
        getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("first_launch_done", true)
            .putString("device_type", selectedDeviceType)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * RecyclerView.Adapter 로 ViewPager2 페이지 인플레이트.
     * Fragment 기반보다 가볍고 빠름 — 페이지 3개 고정이므로 Fragment 필요 없음.
     */
    private inner class PageAdapter : RecyclerView.Adapter<PageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val layoutId = when (viewType) {
                0 -> R.layout.onboarding_page_welcome
                1 -> R.layout.onboarding_page_device
                else -> R.layout.onboarding_page_setup
            }
            val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            return PageViewHolder(view)
        }

        override fun getItemViewType(position: Int): Int = position
        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            when (position) {
                1 -> bindDevicePage(holder.itemView)
                2 -> bindSetupPage(holder.itemView)
                // 0: welcome — 정적 콘텐츠라 바인딩 불필요
            }
        }
    }

    private fun bindDevicePage(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.deviceTypeGroup)
        group.setOnCheckedChangeListener { _, checkedId ->
            selectedDeviceType = when (checkedId) {
                R.id.rbDeviceWear -> "wear"
                R.id.rbDeviceNone -> "none"
                else -> "ble"
            }
        }
    }

    /**
     * 페이지 3 로 진입할 때마다 selectedDeviceType 에 맞춰 텍스트 갱신.
     * onBindViewHolder 는 RecyclerView 풀링 때문에 한번 만 불릴 수 있어,
     * onPageSelected 에서도 호출하도록 ViewPager2 callback 에 추가.
     */
    private fun bindSetupPage(view: View) {
        val tvTitle = view.findViewById<TextView>(R.id.tvSetupTitle)
        val tvBody = view.findViewById<TextView>(R.id.tvSetupBody)
        val (title, body) = when (selectedDeviceType) {
            "wear" -> HelpActivity.TITLE_WEAR to HelpActivity.MSG_WEAR
            "none" -> HelpActivity.TITLE_NO_WATCH to HelpActivity.MSG_NO_WATCH
            else -> HelpActivity.TITLE_BLE to HelpActivity.MSG_BLE
        }
        tvTitle.text = title
        tvBody.text = body
    }

    private inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    /** 페이지 3 으로 swipe 했을 때 selectedDeviceType 반영해 텍스트 갱신. */
    private fun refreshSetupPage() {
        pager.post {
            val recyclerView = pager.getChildAt(0) as? RecyclerView
            recyclerView?.findViewHolderForAdapterPosition(2)?.itemView?.let { bindSetupPage(it) }
        }
    }
}
