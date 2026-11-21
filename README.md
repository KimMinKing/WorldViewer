# WorldViewer

앱을 열면 책장이 먼저 나타난다. 책을 눌러 독서 화면으로 들어가고 뒤로 가기로 책장에 돌아온다.

책장과 독서 도구의 `독서 기록`에서 책별·오늘·전체 읽은 시간을 볼 수 있다. 화면이 켜진 전면 독서만 기록하며, 기록은 기기에 저장되고 백업에 포함된다. `독서 설정`에서는 책별 또는 전체 글자 크기·줄 간격·글자색·배경색을 미리 보고 조절한다.

책장의 `폴더의 TXT 가져오기`에서는 시스템 선택기로 고른 폴더와 하위 폴더만 확인한다. 미리보기에서 원본 바이트가 같은 파일을 걸러내고, 인코딩이 불분명한 파일은 사용자가 선택하거나 건너뛸 수 있다. `EPUB 추가`에서는 DRM 없는 EPUB의 읽기 순서와 장 목록을 가져온다. EPUB 원본도 보존하며 CSS·이미지·내장 폰트·DRM은 현재 지원하지 않는다.

중국어 TXT를 가볍게 읽고, 필요할 때 병음·뜻·문맥 도움을 사용하는 Android 독서 앱 프로젝트다. 병음을 원문 레이아웃 위에 별도로 그려 화면 줄바꿈과 문단 모양을 보존하며, 시스템 파일 선택기로 TXT 원본 바이트를 보존하고 재시작 뒤 다시 열 수 있다. UTF-8과 BOM 있는 UTF-16은 자동 확인하고, BOM 없는 UTF-16·GB18030·Big5는 후보별 미리보기를 사용자가 선택한다. 새 파일 가져오기부터 최근 문서의 검증·표시·선택·임시 발음 수정까지 전체 본문 문자열 대신 스트리밍 처리와 작은 디스크 블록 LRU를 사용한다. 가져오기는 복사·검증 진행 상태와 취소를 지원하고, 강제 종료 뒤 남은 임시 파일만 다음 실행에서 정리한다. 읽던 위치는 문서 해시와 원문 인용문을 포함해 별도 저장하고 재실행 뒤 복원한다. 독서 화면의 진행률 슬라이더는 디스크 인덱스에서 필요한 블록만 찾아 이동하며 마지막 이동 전 위치로 돌아갈 수 있다. 버전형 책장에서 여러 TXT를 추가하고 전환할 수 있으며, 목록 제거는 보존 원본과 읽기 위치를 지우지 않는다. 기본 및 책별 글자 크기·줄 간격·밝은/어두운 테마와 내용 기반 자동 표지를 지원하고, 인덱스 블록을 비동기로 순회해 자동 목차에서 원문 위치로 이동한다.

선택한 중국어의 `뜻`을 누르면 사용자가 등록한 Azure Translator F0로 단어, 문장이나 문단을 한국어로 번역한다. Endpoint는 앱이 공용 주소를 사용하고 Key1은 Android Keystore로 암호화한다. Azure를 설정하지 않은 경우에만 MyMemory 짧은 번역을 선택할 수 있다. 선택 원문이 200자 이하라면 결과의 `뜻 추가`로 저장하고 바로 본문으로 돌아갈 수 있다. 더 긴 선택문은 번역만 가능하며 자동 번역 결과는 영구 저장하지 않는다. 데이터 처리 항목은 [개인정보처리방침 초안](docs/PRIVACY.md)에 기록한다.

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

### 다른 휴대폰에서 시험하기

현재 시험판 APK는 `dist/WorldViewer-0.1.26-trial-2026-11-18.apk`에 있다. Android 8.0 이상 휴대폰으로 **APK 파일 하나만** 복사해 열고 설치 안내를 따르면 된다. 이 파일은 기존 debug 앱과 같은 `dev.worldviewer` 앱 ID·로컬 시험 서명을 사용하므로 같은 PC에서 만든 debug 설치를 덮어쓸 수 있다. 기존 앱을 삭제하면 앱 안의 책과 기록도 지워질 수 있으므로 삭제하지 말고 설치 화면에서 업데이트한다. 다른 서명으로 설치된 같은 앱 ID가 있다면 덮어쓰기가 실패할 수 있다.

새 휴대폰에는 기존 책과 설정이 자동으로 옮겨지지 않는다. 필요하면 기존 앱의 `백업·복원` → `백업 파일 만들기`에서 **원문 파일 포함**을 고르고, 새 휴대폰에서 그 파일을 복원한다. Azure 자격 증명은 백업이나 APK에 포함되지 않으므로 새 휴대폰에서 `Azure 번역`을 처음 누를 때 Key1과 위치/지역을 직접 입력한다. 기본 독서는 Azure 설정 없이도 사용할 수 있다. 이 APK는 실사용 점검용이며 공개 스토어 배포용 서명본은 아니다.

책장 또는 독서 화면 `도구`의 `Azure 번역 사용량`에서 이 앱이 기록한 이번 달 번역 글자 수와 F0 월 200만 자에서 이 기록만 뺀 값을 볼 수 있다. 기존 사용량, 다른 기기·앱과 같은 Azure 리소스의 다른 사용량은 알 수 없으므로 이 값은 실제 잔여 한도가 아니다. 정확한 리소스 사용량은 같은 화면의 `Azure 포털 열기`에서 Translator 리소스의 메트릭 `Text Characters Translated`를 이번 달 합계로 확인한다. 앱의 기록은 수동 백업에 포함되지만 Key1은 포함되지 않는다.

독서 화면은 위 40%를 누르면 이전 화면, 가운데 20%를 누르면 작은 메뉴, 아래 40%를 누르면 다음 화면으로 즉시 이동한다. 연달아 누른 횟수만큼 화면을 넘길 수 있다. 메뉴의 왼쪽 `←`는 책장 복귀, 오른쪽 `☆`는 현재 위치를 즉시 저장하며 저장되면 `★`로 바뀐다. 병음·목차·검색·독서 설정·도구는 가운데 메뉴에 모여 있다. 책장에서는 책을 길게 눌러 표시 이름이나 표지 이미지를 바꿀 수 있다.

독서 화면 맨 아래에는 현재 책의 읽기 진행률과 기기 배터리 잔량이 작은 글씨로 표시된다. 책장에서는 표시되지 않는다.

독서 화면 `도구` → `현재 회차 AI 번역 (DeepSeek)`은 현재 EPUB 장 또는 TXT 자동 목차 회차(최대 15,000자)를 확인한 뒤 DeepSeek Flash API로 보낸다. 사용자가 자신의 API 키를 그때 입력해야 하며 요금이 발생할 수 있다. 원문과 한국어 번역을 구간별로 대조할 수 있지만 결과와 키는 앱에 저장되지 않는다. 회차 제목을 찾지 못하거나 너무 긴 회차는 전송하지 않는다.

책장 위쪽의 `책 제목 검색`은 표시 이름에 들어 있는 글자를 찾는다. 오른쪽에서 `최근 읽은 순` 또는 `제목 순`을 고를 수 있으며, 검색·정렬은 책 목록 저장 순서나 읽던 위치를 바꾸지 않는다.

`독서 설정` → `음량 버튼 동작 (모든 책)`에서 페이지 넘기기를 선택하면 독서 화면에서 음량 올림은 이전 화면, 내림은 다음 화면으로 이동한다. 기본값은 휴대폰 음량 조절이며 책장에서는 항상 일반 음량 버튼으로 동작한다. 이 설정은 재시작 뒤에도 유지되고 수동 백업에 포함된다.

긴 TXT 본문은 원문을 그대로 보존하면서 작은 블록으로 나누어 표시한다. 블록 경계는 가능한 경우 문단·문장 끝을 우선하지만, 줄바꿈 없는 긴 문단은 화면과 블록 크기에 따라 새 줄이 생길 수 있다. `독서 설정` → `화면 넘김 효과 (모든 책)`에서 이전·다음 화면 이동에 짧은 나타남 효과를 선택할 수 있다. 기본값은 즉시 전환이며, 연속 탭도 입력한 횟수만큼 즉시 이동한다.

`독서 설정`에서 TTF·OTF 파일을 고르면 모든 책의 본문 폰트가 바뀐다. 폰트 파일은 앱 안에 복사되어 재시작 뒤에도 유지되고 수동 백업에 포함된다. `기본 폰트로 돌아가기`로 해제할 수 있다. 저장한 `이 구간` 뜻은 해당 원문 글자에 녹색 글자색과 짧은 풀이로 표시된다. 저장된 뜻이 없는 선택에서는 직접 추가하거나 Azure 번역을 명시적으로 요청할 수 있다.

다시 만들려면 `.\gradlew.bat :app:assembleTrial`을 실행한다. Gradle 산출물은 `app/build/outputs/apk/trial/app-trial.apk`이고, `dist/`의 전달용 복사본은 Git에서 제외한다.

개인 debug 앱의 Azure Translator 자격 증명은 Git이 무시하는 저장소 루트의 `.worldviewer-azure.properties`에 `Key1`과 `Region`을 채운 뒤 아래 명령으로 전달할 수 있다. 스크립트는 값을 출력하지 않고 앱 전용 영역으로 보내며, debug 앱은 Android Keystore로 암호화한 다음 휴대폰의 임시 평문을 삭제한다. 이 경로는 release·benchmark 빌드에서 동작하지 않는다.

```powershell
.\tools\install-azure-translator-credentials.ps1
```

성능 회귀에는 release 설정을 상속하지만 별도 앱 ID와 debug 서명을 쓰는 비디버그 가능 `benchmark` 변형을 사용한다. 배포 산출물이 아니다.

```powershell
.\gradlew.bat assembleBenchmark
.\tools\generate-reader-benchmark-fixture.ps1 -OutputPath app\build\tmp\reader-50mib.txt -SizeMiB 50
.\tools\generate-reader-benchmark-fixture.ps1 -OutputPath app\build\tmp\reader-base64-50mib.txt -SizeMiB 50 -ContentProfile DeterministicBase64
```

저장 실패 회귀용 `test-document-provider` 모듈도 제품 앱과 별도 APK로 빌드된다. 에뮬레이터의 시스템 파일 선택기에 읽기 전용 TXT 하위 폴더와 64KiB 뒤 쓰기를 끊는 목적지를 추가하며, 수동 검증 후 제거한다. 제품 APK에는 포함되지 않는다.

```powershell
.\gradlew.bat :test-document-provider:assembleDebug
```

시험 APK에는 `android:testOnly=true`가 지정되어 있다. 필요할 때만 `adb install -t test-document-provider\build\outputs\apk\debug\test-document-provider-debug.apk`로 설치한다.

현재 build·test·lint, API 36 에뮬레이터와 Galaxy Z Flip4(Android 16/API 36)의 설치·실행, 블록 렌더링·선택·오프라인 병음 배치를 확인했다. 기준 기기는 USB ADB 호환 문제로 무선 디버깅을 사용한다. 측정값과 다음 작업은 [docs/STATUS.md](docs/STATUS.md)에 기록한다.
