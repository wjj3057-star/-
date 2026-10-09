# 오늘출석 · Academy Attendance

선생님이 학생의 학원 출석을 관리하고 학부모에게 출석 문자를 보내는 Android 앱입니다. **버전 1.1.0**.
한국어 UI, Android 8.0(API 26) 이상, 휴대폰·태블릿 레이아웃을 지원합니다.

## 사용 방법

1. **설정 → 반 추가**에서 반 이름, 수업 요일, 시작 시간을 입력합니다.
2. **학생 관리 → 학생 등록**에서 학생 이름과 소속 반을 등록합니다. 지난 출석을 입력하려면 등록일을 해당 날짜 이전으로 지정합니다.
3. **출석 체크**에서 날짜를 선택하고 **출석 / 지각 / 결석 / 공결**을 누릅니다. 즉시 저장됩니다. 선택한 상태를 다시 누르면 미확인으로 돌아가고, 하단 알림의 **되돌리기**로 마지막 변경을 취소할 수 있습니다.
4. 학생 카드의 메모 버튼에서 출석 사유를 적습니다. **미확인 모두 출석**은 현재 반·검색 조건에 보이는 미확인 학생에게만 적용됩니다.
5. **출석 기록**에서 월별 출석률·달력·학생별 기록을 확인하고 CSV로 내보냅니다.
6. **설정 → 전체 데이터 백업**으로 JSON 파일을 저장합니다. **백업 파일 복원**은 파일 검증과 확인을 거쳐 현재 데이터를 교체합니다.

학생 추가 시 반이 없으면 반 생성 화면이 먼저 열립니다. 퇴원한 학생은 삭제 대신 보관하며, 저장된 출석 기록은 유지됩니다. 보관된 학생의 정보 수정·보관 해제도 가능합니다.

## 연락처로 학생·학부모 등록

1. **학생 관리 → 연락처에서 자동 등록 → 연락처에서 찾기**를 누르고 연락처 읽기 권한을 허용합니다.
2. `김하늘 어머니`, `김하늘아버지`, `(김하늘)어머니`, `김하늘(어머니)`처럼 저장된 이름을 학생별로 묶습니다. 기본 키워드는 어머니·어머님·엄마·모친, 아버지·아버님·아빠·부친, 보호자, 학생이며 **설정 → 연락처 인식 키워드**에서 바꿀 수 있습니다.
3. 인식된 학생 이름, 학부모 번호, 소속 반을 확인하고 **선택한 학생 등록**을 누릅니다. 기존 학생은 이름이 같으면 연결 후보로 표시합니다. 동명이인은 반을 보고 직접 선택해야 합니다. 키워드 없는 학생 본인 번호는 인식된 학생 이름과 정확히 일치할 때만 후보로 표시합니다.
4. **학생 정보 수정**에서도 학생과 학부모 연락처를 하나씩 선택하거나 번호를 직접 입력할 수 있습니다. 학생당 학부모 번호는 최대 6개이며, 수신 여부를 각각 선택할 수 있습니다.

연락처는 사용자가 요청할 때 읽는 방식입니다. 휴대폰 주소록을 수정하거나 실시간 동기화하지 않습니다. 같은 번호를 다시 가져오면 중복 추가하지 않으며, 기존 학생의 다른 번호를 자동 덮어쓰지 않습니다. 변경된 번호는 학생 정보에서 수정하세요. 자동 등록 자체는 문자를 보내지 않습니다.

## 출석 문자 설정

1. **설정 → 출석 문자 설정**에서 **문자 권한 허용**을 누릅니다. SMS가 가능한 Android 휴대폰과 기본 문자용 SIM이 필요합니다.
2. **출석 체크 시 자동 발송**을 켜고 발송할 출결 상태와 문구를 저장합니다. 첫 설치·기존 버전 업데이트·백업 복원 시 자동 발송은 꺼져 있습니다. 기본 발송 대상 상태는 **출석·지각**이며 결석·공결도 선택할 수 있습니다.
3. 선생님이 **오늘 날짜**의 출결 상태를 새로 체크하거나 변경하면 먼저 출석을 저장한 뒤, 해당 학생의 수신 설정된 학부모 번호로 SMS를 요청합니다. 일괄 출석에도 적용됩니다.
4. **설정 → 문자 발송 기록**에서 최근 200건을 확인합니다. 발송 완료는 통신사 전송 응답이며 학부모의 수신·읽음 확인이 아닙니다. 실패하거나 결과가 확인되지 않은 오늘의 문자는 수신 여부를 확인한 후 명시적으로 재전송할 수 있습니다.

기본 문구:

```text
[{학원명}] {학생이름} 학생이 {날짜} {시간}에 {상태} 처리되었습니다.
```

직접 문구를 편집하거나 `{학원명}`, `{학생이름}`, `{반이름}`, `{날짜}`, `{시간}`, `{상태}`, `{선생님}`, `{메모}`를 삽입할 수 있습니다. 예시 미리보기와 기본 문구 복원 기능이 있습니다. 템플릿은 500자, 치환한 실제 문자는 1,000자·10개 SMS 분량 이내입니다. 요금제에 따라 문자 요금이 발생하고 긴 문구는 여러 SMS로 나뉩니다.

메모만 수정, 과거 기록 수정, 미확인 복귀, 되돌리기, 백업 복원은 발송하지 않습니다. 같은 학생·날짜·출결 상태·정규화한 수신 번호는 자동 재발송하지 않습니다. 발송 요청을 기기에 먼저 기록하므로 재실행·되돌리기 후에도 중복 방지가 유지됩니다. 이미 발송한 문자는 취소할 수 없습니다. 불확실한 요청은 자동 재시도하지 않습니다.

Android 버전·설치 방식·기기 정책에 따라 문자 권한 부여가 제한될 수 있습니다. 권한이 거부되면 앱 권한 설정에서 확인하세요. 권한이나 SIM이 없어도 학생·출석 관리는 이용할 수 있습니다. 이 앱은 SMS 기본 앱 역할을 요구하지 않으며 서버 문자 서비스는 사용하지 않습니다.

## 동작 범위

- 한 학생당 소속 반 하나, 날짜별 출석 기록 하나를 관리합니다.
- 출석률 = (출석 + 지각) / (출석 + 지각 + 결석). 공결·미확인은 계산에서 제외하며, 미확인을 자동으로 결석 처리하지 않습니다.
- 시간은 **오늘** 출석 또는 지각을 처음 체크한 기기 시간을 기록합니다. 과거 날짜에는 시간을 만들어 넣지 않습니다. 미래 날짜는 조회만 가능합니다.
- 저장된 기록에는 당시 학생 이름과 반 이름을 보관합니다. 이름·반 변경 후에도 과거 기록은 바뀌지 않습니다. 요일 및 반 변경은 아직 기록하지 않은 날짜의 명단에도 적용됩니다.
- 학생을 다시 활성화하면 기존 등록일부터 미기록 명단에도 다시 표시됩니다. 재등록 기간을 별도로 관리하는 기능은 없습니다.
- 학생·학부모 정보, 출석 기록, 문자 발송 기록은 기기 내부 앱 전용 파일에 저장됩니다. 인터넷 권한, 계정, 서버, 광고, 분석 도구가 없습니다. 연락처 읽기와 문자 발송 권한은 해당 기능을 요청할 때만 확인합니다. 여러 기기 자동 동기화·학생용 키오스크는 포함하지 않습니다.
- 기기 변경 또는 앱 삭제 전 JSON 백업을 저장해 주세요. CSV는 열람용이고, 전체 복원에는 JSON 백업이 필요합니다.
- JSON 백업은 암호화하지 않습니다. 학생·학부모 이름, 전화번호와 기록을 포함하므로 본인이 관리하는 위치에 보관하세요. 문자 발송 이력은 백업·복원 대상에서 제외합니다. 기존 1.0 백업도 가져올 수 있습니다.

## 프로젝트 구성

```text
app/src/main/assets/       한국어 화면, 상태 관리, 검증·통계 로직
app/src/main/java/         Android WebView, 연락처 조회, SMS 발송·결과 기록, 파일 저장
app/src/main/res/          테마와 런처 아이콘
scripts/build_apk.py       SDK만으로 APK 빌드·정렬·서명
tests/core.test.cjs        출석·날짜·통계·백업·CSV 테스트
tests/ui.cjs               실제 화면 조작 및 모바일·태블릿 레이아웃 테스트
tests/contacts-sms.test.cjs 연락처 이름 인식·병합·마이그레이션·문자 대상 테스트
tests/contacts-ui.cjs      모의 연락처·권한·SMS 브리지를 이용한 화면 통합 테스트
tests/SmsRulesTest.java    Java 번호 정규화·중복 키·템플릿 테스트
```

런타임 외부 라이브러리가 없습니다. Java와 Android 표준 API로 만든 앱 안에서 번들 HTML/CSS/JavaScript를 표시합니다. WebView는 허용 목록에 있는 앱 자산만 제공하며 외부 탐색, 파일 URL 접근, 혼합 콘텐츠를 차단합니다. 백업의 모든 표시 문자열은 HTML 이스케이프하고, CSV 수식 입력도 무력화합니다. 저장 실패 시 화면 상태를 성공으로 바꾸지 않습니다.

## APK 빌드 — PowerShell

필요 도구: JDK 17, Python 3.10 이상, Android SDK의 **Platform 35**와 **Build Tools 35.0.0**. Android Studio의 SDK Manager에서 설치할 수 있습니다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# JAVA_HOME은 설치한 JDK 17 경로로 지정하세요. java가 PATH에 있으면 생략할 수 있습니다.
# $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
python .\scripts\build_apk.py
```

결과: `out\AcademyAttendance.apk`. 이 빌드 경로는 Gradle 또는 Maven 의존성 다운로드 없이 동작합니다. Android Studio 프로젝트로 열어 Gradle 8.9 / Android Gradle Plugin 8.7.3으로 빌드할 수도 있습니다.

### 같은 서명으로 업데이트

처음 빌드하면 사용자 홈의 `.android\academy-attendance-debug.keystore`에 개발용 서명 키가 생성됩니다. 이미 설치한 앱을 데이터 유지 상태로 업데이트하려면 **같은 키**를 재사용하고 `versionCode`를 높여야 합니다. 키 파일을 GitHub에 올리지 마세요.

전달된 APK는 별도로 보관한 `AcademyAttendance-signing.keystore`로 서명했습니다. 재빌드 시 그 키를 사용하려면:

```powershell
python .\scripts\build_apk.py --keystore "C:\MyPrivateKeys\AcademyAttendance-signing.keystore"
```

개발용 키 별칭은 `attendance`, 비밀번호는 `android`입니다. 앱스토어 배포용 키 관리 및 배포 설정은 별도로 구성해야 합니다.

## 검증

Node.js 18 이상:

```powershell
node --test .\tests\core.test.cjs .\tests\contacts-sms.test.cjs
node --check .\app\src\main\assets\app.js
node --check .\app\src\main\assets\contacts-sms.js
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d out\sms-tests .\app\src\main\java\kr\academy\attendance\SmsRules.java .\tests\SmsRulesTest.java
java -cp out\sms-tests SmsRulesTest
```

선택적으로 Playwright를 설치해 화면 흐름을 확인할 수 있습니다.

```powershell
npm install --no-save playwright
npx playwright install chromium
node .\tests\ui.cjs
node .\tests\contacts-ui.cjs
```

핵심 로직 16개 테스트와 Java 규칙 테스트를 제공합니다. 기존 UI 테스트는 반·학생 등록, 저장 후 다시 열기, 메모, 일괄 처리와 되돌리기, 검색, 통계, CSV, 백업·복원, 보관 기록 유지, 미래 날짜 차단, 입력 이스케이프, 저장 실패 시 이전 상태 유지, 320/390/800px 화면의 가로 넘침 여부를 확인합니다. 연락처·문자 UI 테스트는 동명이인 연결, 반복 가져오기, 개별 연락처 선택, 권한 거부, SIM 확인, 문구 편집, 저장 성공 후 발송 요청, 메모·과거 기록 제외, 복원 시 자동 발송 해제, 결과 표시·재전송 확인을 검증합니다. 테스트는 가상 연락처와 SMS 호출을 사용하며 실제 문자를 보내지 않습니다.

빌드 스크립트는 Java 컴파일, Android DEX 생성, 리소스 패키징, `apksigner verify`, `zipalign -c`를 실행합니다. 실제 Android 기기의 설치·권한 부여·연락처 조회·통신사 문자 송수신은 기기별 확인이 필요합니다. 실기기 및 통신망 발송 테스트는 이 개발 환경에서 수행하지 않았습니다.

개발용 화면 미리보기:

```powershell
python -m http.server 8765 --directory .\app\src\main\assets
```

`http://localhost:8765`를 브라우저에서 엽니다. 브라우저 미리보기는 브라우저의 로컬 저장소를 사용하며 APK와 데이터가 공유되지 않습니다.

참고한 Android 공식 문서: [앱 내 콘텐츠 로드](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content), [시스템 파일 선택기로 문서 저장·열기](https://developer.android.com/training/data-storage/shared/documents-files), [연락처 이름 조회](https://developer.android.com/identity/providers/contacts-provider/retrieve-names), [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager), [Android 권한](https://developer.android.com/reference/android/Manifest.permission).
