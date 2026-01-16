# PPPGreenDemo (Wear OS) — PPG Green (25 Hz) using Samsung Health Tracking SDK 1.4.1

간단한 Wear OS 앱으로, Galaxy Watch의 **PPG Green** 신호를 연속 수집(`PPG_CONTINUOUS`)하여 CSV로 저장합니다.

## 빠른 사용법
1) Android Studio에서 **Open** > 이 프로젝트 폴더를 열기  
2) `app/libs/`에 **samsung-health-tracking-1.4.1.aar** 가 포함되어 있습니다. (이미 복사해 두었어요)  
3) 워치(실기기, 에뮬레이터 불가)를 연결하고 **Run**.  
4) 앱에서 **Start** 버튼을 누르면 수집 시작 → `Android/data/com.example.pppgreendemo/files/Download/PPG/PPG_yyyy-MM-dd_HH-mm-ss_GREEN.csv` 로 저장됩니다.

## 주요 포인트
- API: `HealthTrackerType.PPG_CONTINUOUS` + `PpgType.GREEN` 만 요청 → 배터리 절약.  (선택셋 전달 필수)  
- 이벤트 콜백: `HealthTracker.setEventListener(TrackerEventListener)` 로 **DataPoint 리스트**를 수신.  
  - 값 추출: `dp.getValue(ValueKey.PpgSet.PPG_GREEN)` (정수), 상태: `ValueKey.PpgSet.GREEN_STATUS`  
  - 타임스탬프: `dp.getTimestamp()` (Epoch ms)
- 퍼미션: Android 15(API 35)이하 `BODY_SENSORS`, Android 16(API 36)+에서는
  `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` 필요(코드에서 조건부 요청).
- 파일 저장: 앱 전용 외부 디렉터리(권한 불필요).

## 문서
- `HealthTrackerType.PPG_CONTINUOUS` 및 `PPG_ON_DEMAND` + `PpgType` 셋 지정: 공식 문서 참고.  
- PPG 값 키: `ValueKey.PpgSet.PPG_GREEN`, 상태 키: `ValueKey.PpgSet.GREEN_STATUS`.  
- DataPoint 인터페이스: `getValue()`, `getTimestamp()`.

## 주의
- **Galaxy Watch 실기기**에서만 동작합니다. 에뮬레이터는 지원하지 않습니다.
- 삼성 **Health Platform** 앱이 워치에 설치/최신 버전이어야 합니다. 연결 실패 시 `exception.resolve(activity)`가 마켓으로 안내합니다.
- 연속 수집이므로 배터리 영향이 있을 수 있습니다. 필요 시 `ppgTracker.flush()`를 간헐적으로 호출해 배치 데이터를 즉시 가져올 수 있습니다.

---
### License
Provided as-is for educational/demo purposes.