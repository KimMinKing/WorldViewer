# WorldViewer

중국어 TXT를 가볍게 읽고, 필요할 때 병음·뜻·문맥 도움을 사용하는 Android 독서 앱 프로젝트다. 병음을 원문 레이아웃 위에 별도로 그려 화면 줄바꿈과 문단 모양을 보존하며, 시스템 파일 선택기로 TXT 원본 바이트를 보존하고 재시작 뒤 다시 열 수 있다. UTF-8과 BOM 있는 UTF-16은 자동 확인하고, BOM 없는 UTF-16·GB18030·Big5는 후보별 미리보기를 사용자가 선택한다. 새 파일 가져오기부터 최근 문서의 검증·표시·선택·임시 발음 수정까지 전체 본문 문자열 대신 스트리밍 처리와 작은 디스크 블록 LRU를 사용한다. 가져오기는 복사·검증 진행 상태와 취소를 지원하고, 강제 종료 뒤 남은 임시 파일만 다음 실행에서 정리한다. 읽던 위치는 문서 해시와 원문 인용문을 포함해 별도 저장하고 재실행 뒤 복원한다. 독서 화면의 진행률 슬라이더는 디스크 인덱스에서 필요한 블록만 찾아 이동하며 마지막 이동 전 위치로 돌아갈 수 있다. 버전형 책장에서 여러 TXT를 추가하고 전환할 수 있으며, 목록 제거는 보존 원본과 읽기 위치를 지우지 않는다. 기본 및 책별 글자 크기·줄 간격·밝은/어두운 테마와 내용 기반 자동 표지를 지원하고, 인덱스 블록을 비동기로 순회해 자동 목차에서 원문 위치로 이동한다.

선택한 중국어는 앱에 포함된 CC-CEDICT 2026-09-26 고정 색인에서 간체·번체 표제어, 병음과 복수 영어 뜻을 찾는다. 사용자가 명시적으로 요청하면 [Google ML Kit 기기 내 번역](https://developers.google.com/ml-kit/language/translation/android)으로 영어 뜻을 한국어화하며 영어 원문도 함께 표시한다. 첫 요청에는 영어↔한국어 모델 다운로드가 필요하며 Android 16 에뮬레이터에서 약 52MB를 관측했다. 결과 창의 `온라인 번역`에는 사용자가 등록한 Azure Translator F0의 Key1과 지역을 입력해 선택한 긴 문장이나 문단을 직접 한국어로 번역할 수 있다. Endpoint는 앱이 공용 주소를 사용하고 Key1은 Android Keystore로 암호화한다. Azure를 설정하지 않으면 등록 없는 MyMemory 짧은 번역을 선택할 수 있다. 자동 번역 결과는 영구 저장하지 않고 전문 중국어→한국어 사전과 같다고 표시하지 않는다. 데이터 처리 항목은 [개인정보처리방침 초안](docs/PRIVACY.md)에 기록한다.

- 제품 기준: [worldviewer-blueprint-v0.1.md](worldviewer-blueprint-v0.1.md)
- 개발 계획과 단계별 완료 조건: [docs/PLAN.md](docs/PLAN.md)
- 현재 환경과 검증 상태: [docs/STATUS.md](docs/STATUS.md)
- 개인정보처리방침 배포 전 초안: [docs/PRIVACY.md](docs/PRIVACY.md)
- 저장소 작업 규칙: [AGENTS.md](AGENTS.md)
- 앱에 포함되는 제3자 코드·데이터 고지: [app/src/main/assets/licenses/third_party_notices.txt](app/src/main/assets/licenses/third_party_notices.txt)

## 빌드와 설치

JDK 17 이상과 Android SDK API 36이 필요하다. 로컬 SDK 경로는 Git에 포함되지 않는 `local.properties`의 `sdk.dir`에 설정한다.

```powershell
.\gradlew.bat assembleDebug test lint
```

debug APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. ADB에 실제 기기가 `device` 상태로 연결되면 다음 명령으로 설치한다.

```powershell
.\gradlew.bat :app:installDebug
```

성능 회귀에는 release 설정을 상속하지만 별도 앱 ID와 debug 서명을 쓰는 비디버그 가능 `benchmark` 변형을 사용한다. 배포 산출물이 아니다.

```powershell
.\gradlew.bat assembleBenchmark
.\tools\generate-reader-benchmark-fixture.ps1 -OutputPath app\build\tmp\reader-50mib.txt -SizeMiB 50
.\tools\generate-reader-benchmark-fixture.ps1 -OutputPath app\build\tmp\reader-base64-50mib.txt -SizeMiB 50 -ContentProfile DeterministicBase64
```

저장 실패 회귀용 `test-document-provider` 모듈도 제품 앱과 별도 APK로 빌드된다. 에뮬레이터의 시스템 파일 선택기에 읽기 전용 목적지와 64KiB 뒤 쓰기를 끊는 목적지를 추가하며, 수동 검증 후 제거한다. 제품 APK에는 포함되지 않는다.

```powershell
.\gradlew.bat :test-document-provider:assembleDebug
```

시험 APK에는 `android:testOnly=true`가 지정되어 있다. 필요할 때만 `adb install -t test-document-provider\build\outputs\apk\debug\test-document-provider-debug.apk`로 설치한다.

현재 build·test·lint, API 36 에뮬레이터와 Galaxy Z Flip4(Android 16/API 36)의 설치·실행, 블록 렌더링·선택·오프라인 병음 배치를 확인했다. 기준 기기는 USB ADB 호환 문제로 무선 디버깅을 사용한다. 측정값과 다음 작업은 [docs/STATUS.md](docs/STATUS.md)에 기록한다.
