# 공개 배포 결정안 — 2026-10-12

이 문서는 공개 배포를 실행하기 전에 결정할 항목의 제안이다. 계정 등록, 공개 게시, 앱 ID 변경과 공개 서명 키 생성은 아직 하지 않았다. `docs/PLAN.md`의 12단계 수용 검증도 진행 중이다.

## 현재 상태와 권장안

| 항목 | 확인한 현재 상태 | 권장 결정 |
| --- | --- | --- |
| 공개 이름 | `WorldViewer`는 가칭이며 이름·상표 사용 가능성을 확인하지 않았다. | 당분간 `WorldViewer`를 후보로 사용하고 공개 등록 전에 이름 중복·상표를 확인한다. |
| 배포 경로 | `trial`은 `dev.worldviewer`와 로컬 Android debug 서명을 사용한다. `release`는 공개용으로 서명되지 않았다. | Google Play의 비공개 테스트를 첫 배포 경로로 선택하고, 12단계 수용 검증을 마친 뒤 공개 여부를 결정한다. 현재 APK는 개인 시험용으로 유지한다. |
| 영구 앱 ID | `dev.worldviewer`는 이미 시험 설치에 사용됐으나 공개용 서명과 데이터 이전 방침이 없다. | 공개판에 별도 앱 ID 후보 `dev.worldviewer.reader`를 사용한다. 최종 값은 소유자가 승인하고 Play Console에서 등록 가능 여부를 확인한 뒤 고정한다. Kotlin `namespace`는 별도로 유지할 수 있다. |
| 서명 | `trial`과 `benchmark`는 debug 서명, `release`는 미서명이다. | 공개판은 전용 업로드 키로 AAB를 서명하고 Play App Signing의 앱 서명 키는 Google이 생성·관리하도록 한다. 업로드 키와 암호는 저장소 밖에 보관하고 별도 복구 사본을 둔다. 다른 스토어에 동일 서명으로 배포할 계획이 있으면 키 선택 전에 다시 결정한다. |
| 개발자·연락처 | 배포 주체와 공개 연락처가 없다. | 실제 Play 개발자 계정 주체의 표시 이름과 사용자가 받을 문의 이메일을 확정한다. 계정 유형에 따라 추가 공개 연락처 요구가 달라질 수 있어 계정 화면에서 확인한다. |
| 개인정보처리방침 | `docs/PRIVACY.md`는 현재 코드 대조를 마친 초안이지만 운영 주체·연락처·시행일·공개 URL이 비어 있고 앱 안의 방침 진입점이 없다. | 주체·연락처를 채운 한국어 방침을 공개 HTTPS 주소에 게시하고 앱 안에서도 열 수 있게 한다. 공개판의 온라인 번역 경로와 Data safety 응답은 최종 빌드로 다시 대조한다. |

Google Play의 신규 앱·업데이트는 현재 Android 16/API 36 이상을 대상으로 해야 하며, 현 앱의 `targetSdk=36`은 이 항목을 충족한다. [Play 대상 API 요건](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)

신규 Play 앱은 App Bundle과 Play App Signing을 사용한다. 업로드 키와 앱 서명 키를 분리할 수 있고, Play가 앱 서명 키를 생성하면 개발자가 그 개인 키를 받아 다른 스토어에 사용할 수 없다. [Android 앱 서명 안내](https://developer.android.com/studio/publish/app-signing), [Play 업로드 안내](https://developer.android.com/studio/publish/upload-bundle)

새 개인 개발자 계정이라면 공개 프로덕션 접근 전에 최소 12명이 연속 14일 참여하는 비공개 테스트가 필요하다. 계정 유형·생성 시기에 따라 적용 여부를 확인해야 한다. [Play 개인 계정 테스트 요건](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)

Google Play는 앱 안과 Play Console에서 접근 가능한 개인정보처리방침을 요구한다. 온라인 번역은 사용자가 명시적으로 요청하는 외부 전송이므로 최종 Data safety 답변을 현재 오프라인판의 ‘무수집·무공유’ 판정에서 복사하지 않는다. [사용자 데이터 정책](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [Data safety 작성 안내](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

## 기존 시험판 데이터 이전

권장한 새 앱 ID를 쓰면 공개판은 시험판과 나란히 설치되며 Android의 앱 업데이트로 시험판 데이터를 자동 승계하지 않는다. 시험판에서 `.wvb` 백업을 **원문 포함**으로 만들고, 새 앱에 복원해 책·위치·주석·설정을 대조한 뒤에만 기존 설치를 정리한다. Azure Key1은 백업에 없으므로 새 앱에서 다시 입력해야 한다. 같은 `dev.worldviewer` ID를 공개판에 쓰더라도 debug 서명과 공개 서명이 달라 기존 시험판 위에 직접 업데이트할 수 없다. Android는 업데이트 시 앱 서명 인증서 일치를 요구한다. [Android 앱 서명 안내](https://developer.android.com/studio/publish/app-signing)

## 확정에 필요한 입력

1. 첫 공개 경로: Google Play 비공개 테스트 권장. APK 직접 배포만 원한다면 배포·서명·개발자 확인 경로를 다시 설계한다.
2. 공개 이름과 영구 앱 ID: 위 후보 승인 또는 변경. 이름·ID 사용 가능성은 실제 등록 전에 확인한다.
3. 개발자 계정 주체(개인/조직), 공개 표시 이름, 문의 이메일, 개인정보처리방침을 게시할 HTTPS 주소 또는 호스팅 계정.
4. Google 관리 앱 서명 키 + 별도 업로드 키 사용 여부와 다른 스토어에 같은 서명이 필요한지 여부.

이 네 항목을 확정한 뒤 공개용 빌드 설정, 앱 내 방침 진입점, 서명 AAB와 시험판→공개판 백업·복원 검증을 한 작업씩 진행한다. TalkBack의 보고된 비정상 동작, 휴대폰 30분 수동 독서, 실제 클라우드 문서 공급자와 배포권리 검증은 공개 전에 별도로 남아 있다. 실제 태블릿 검증은 사용자 요청에 따라 보류 상태다.
