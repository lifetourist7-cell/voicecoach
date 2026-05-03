package com.jecheon.voicecoach

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 도움말 페이지 — 카드 리스트.
 *
 * 메인 화면 상단 (?) 아이콘에서 진입. 첫 실행 후에도 언제든 다시 볼 수 있도록.
 * 각 카드는 짧은 요약을 보여주고, 탭하면 상세 다이얼로그.
 */
class HelpActivity : AppCompatActivity() {

    /** 시스템 폰트 스케일 무시 — 항상 1.0x 로 렌더링. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        applyEdgeToEdge()

        findViewById<ImageButton>(R.id.btnHelpBack).setOnClickListener { finish() }

        // ─── 워치 셋업 ───
        bindCard(R.id.cardBleSetup, TITLE_BLE, MSG_BLE)
        bindCard(R.id.cardWearSetup, TITLE_WEAR, MSG_WEAR)
        bindCard(R.id.cardNoWatch, TITLE_NO_WATCH, MSG_NO_WATCH)

        // ─── 모드 사용법 ───
        bindCard(R.id.cardModeRunning, TITLE_MODE_RUNNING, MSG_MODE_RUNNING)
        bindCard(R.id.cardModeGolf, TITLE_MODE_GOLF, MSG_MODE_GOLF)
        bindCard(R.id.cardModeMeditation, TITLE_MODE_MEDITATION, MSG_MODE_MEDITATION)

        // ─── 추가 기능 ───
        bindCard(R.id.cardCoach, TITLE_COACH, MSG_COACH)
        bindCard(R.id.cardBreath, TITLE_BREATH, MSG_BREATH)
    }

    private fun bindCard(id: Int, title: String, message: String) {
        findViewById<View>(id).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show()
        }
    }

    companion object {
        // 워치 셋업 — MainActivity 의 첫 실행 가이드와 동일 텍스트 재사용
        const val TITLE_BLE = "BLE 송출 워치"
        const val MSG_BLE =
            "가민 / 폴라 / 순토 등 블루투스 심박수 프로파일 (Heart Rate Service 0x180D) 을 송출하는 워치입니다.\n\n" +
            "사용 순서\n" +
            "1) 워치에서 '심박수 송출' 또는 'Broadcast HR' 메뉴 활성화\n" +
            "2) 본 앱 시작 → 자동 스캔 / 페어링\n" +
            "3) 페어링되면 '심박수 수신 중' 표시\n\n" +
            "팁\n• 가민 — 설정 > 센서 및 액세서리 > 심박수 > 송출 모드\n" +
            "• 폴라 — 설정 > 일반 설정 > 페어링 및 동기화\n" +
            "• 워치를 차고 있어야 측정값이 나옵니다."

        const val TITLE_WEAR = "Wear OS 워치"
        const val MSG_WEAR =
            "갤럭시워치 4 이상 / 픽셀워치 / OnePlus Watch 등 Wear OS 3+ 기반 워치입니다.\n\n" +
            "사용 순서\n" +
            "1) 폰에 본 앱 설치 — 워치에도 컴패니언 앱이 자동 설치됨 (Play Store 통해 설치 시)\n" +
            "2) 워치에서 VoiceCoach 앱을 한 번 실행 → 심박수 / 알림 권한 허용\n" +
            "3) 폰 앱 시작 → 워치에 자동으로 측정 시작 알림\n" +
            "4) 폰 앱에 '심박수 수신 중 (워치)' 표시\n\n" +
            "팁\n• 갤럭시워치 — Galaxy Wearable 앱으로 폰과 페어링 필요\n" +
            "• 컴패니언 앱이 자동 설치되지 않으면 워치 Play Store 에서 'VoiceCoach' 검색\n" +
            "• 갤럭시워치 3 / Gear S3 (Tizen) 는 지원하지 않습니다."

        const val TITLE_NO_WATCH = "워치 없이 사용"
        const val MSG_NO_WATCH =
            "심박수 입력이 없어도 다음 기능은 그대로 사용할 수 있습니다.\n\n" +
            "• 골프 — 3:1 템포 메트로놈, 클럽 BPM 프리셋\n" +
            "• Meditation — 호흡 기법 4종, 호흡 가이드\n" +
            "• 노이즈 마스킹 — 화이트 / 핑크 / 브라운 / 사용자 정의\n" +
            "• 자동 종료 타이머\n\n" +
            "심박수 안내, 보이스 코치 임계값, 1분 평균 HR 안내는 워치 연결이 있어야 동작합니다."

        // 모드 사용법
        const val TITLE_MODE_RUNNING = "달리기 모드"
        const val MSG_MODE_RUNNING =
            "심박수와 페이스를 음성으로 안내합니다.\n\n" +
            "1) 상단 모드에서 '달리기' 선택\n" +
            "2) HR / 페이스 토글로 안내 항목 선택\n" +
            "3) 안내 간격 (초) 설정\n" +
            "4) 시작 → 화면 꺼져도 계속 음성 안내\n\n" +
            "보이스 코치 임계값을 켜면 심박수가 범위를 벗어날 때 자동 안내."

        const val TITLE_MODE_GOLF = "골프 모드"
        const val MSG_MODE_GOLF =
            "스윙 템포용 3:1 메트로놈입니다.\n\n" +
            "1) 클럽 프리셋 (드라이버 / 아이언 / 웨지 / 퍼터) 선택 — BPM 자동 입력\n" +
            "2) 메트로놈 시작 — 첫 박자 강조음\n" +
            "3) 시각 피드백 옵션 — 후면 플래시 또는 화면 깜빡임\n\n" +
            "팁\n• 클럽 버튼을 길게 누르면 BPM 직접 편집\n• 화면 꺼짐 후에도 시각 피드백 유지"

        const val TITLE_MODE_MEDITATION = "Meditation 모드"
        const val MSG_MODE_MEDITATION =
            "호흡 훈련 + 심박수 변화 추적입니다.\n\n" +
            "1) 호흡 기법 선택 (박스 / 4-7-8 / 4-2-6 / 사용자 정의)\n" +
            "2) 호흡 가이드 시작 — 시각 / 음성 안내\n" +
            "3) 시작 후 1분마다 직전 1분 평균 심박수 변화 음성 안내\n\n" +
            "심박수 표시 아래 슬라이더로 평균 윈도우 (1~10초) 조정 가능."

        // 추가 기능
        const val TITLE_COACH = "보이스 코치 임계값"
        const val MSG_COACH =
            "달리기 모드 전용 — 심박수가 설정 범위를 벗어나면 자동 음성 안내.\n\n" +
            "1) '코치' 토글 ON\n" +
            "2) 톱니바퀴 아이콘으로 코치 설정 진입\n" +
            "3) 하한 / 상한 BPM 설정\n\n" +
            "범위를 벗어나면 '심박수 너무 높음', '천천히 가세요' 같은 음성 안내."

        const val TITLE_BREATH = "호흡 기법 4종"
        const val MSG_BREATH =
            "Meditation 모드의 호흡 프리셋입니다.\n\n" +
            "• 박스 호흡 (4-4-4-4) — 균형 / 집중. 군 / 명상 입문에 추천\n" +
            "• 4-7-8 호흡 — 깊은 이완. 잠들기 전 추천\n" +
            "• 4-2-6 호흡 — 부교감 활성. 스트레스 해소\n" +
            "• 사용자 정의 — 들이마심 / 멈춤1 / 내쉼 / 멈춤2 직접 설정 (각 1~30초)\n\n" +
            "메트로놈 토글을 켜면 호흡 박자에 맞춰 부드러운 클릭음."
    }
}
