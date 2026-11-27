# 국회의원 정기 재산공개 CSV 연결 검증

2026-11-27 기준으로 2025·2026년 정기공개 자료를 로컬 CSV로 읽었다. 국회의장·부의장·국회의원의 상장주식 신고만 추출했다. 재산 기준일, 공개일과 원천 레코드 번호를 각 행에 보존했다. 정기공개는 연 1회이며 신규·퇴직 등의 수시공개는 별도 회차다. [공직윤리시스템](https://www.peti.go.kr/prptOptp.do)

| 공개 연도 | 재산 기준일 / 공개일 | 직접 읽은 자료 | 상장주식 신고 행 | 본인 행 |
|---|---|---|---:|---:|
| 2025 | 2024-12-31 / 2025-03-27 | 정보공개센터 GitHub가 연결한 공개 시트의 `상세` 탭. 헤더를 제외한 6,416행의 직위·이름·재산구분·명의·종류·권리 명세를 조회 | 254 | 60 |
| 2026 | 2025-12-31 / 2026-03-26 | 국회공보 제2026-54호를 파싱한 GitHub CSV. 헤더를 제외한 6,498개 레코드를 읽음 | 247 | 58 |

2025년 자료는 [정보공개센터 저장소](https://github.com/opengirok/congress_asset_disclosure), [공개 시트 상세 탭](https://docs.google.com/spreadsheets/d/182m4MFFj4Ho2cICo3PGy8RyxHZ3vwNklyo5yM4M5M4Q/edit#gid=1164580719), [국회사무처 발표](https://lnp.nanet.go.kr/policy/assemblyBodo/detail.do?controlNo=168626)에서 출처를 확인할 수 있다. 2026년 자료는 [원천 CSV의 고정 버전](https://github.com/yongkyu4803/NA_AssetReview/blob/52f081965bbf0e96ea076661c84c6ef7e720b976/data/source/재산공개_파싱.csv)과 [수집 메타데이터](https://github.com/yongkyu4803/NA_AssetReview/blob/52f081965bbf0e96ea076661c84c6ef7e720b976/data/source/metadata.json)를 사용했다. CSV의 Git blob SHA는 `9134e05647bed9235174c23983566c539669fdda`다.

### 실제 대조 결과

각 공개분에서 의원·명의 관계·종목을 맞춘 뒤 권리 명세의 보유 수량을 읽었다. 한 명의 서로 다른 종목이 한 셀에 담기는 사례, 괄호 안에 증가·감소 수량이 따로 적힌 사례가 있다. `sourceRecord`는 헤더를 1로 세는 시트 행 또는 CSV 레코드 번호다.

| 의원 | 명의 | 종목 | 2025 수량 | 2026 수량 | 차이 | 원천 위치 2025 → 2026 |
|---|---|---|---:|---:|---:|---|
| 안철수 | 본인 | 안랩 053800 | 1,860,000 | 1,860,000 | 0 | 3089 → 3137 |
| 고동진 | 본인 | 삼성전자 005930 | 48,500 | 36,000 | −12,500 | 245 → 233 |
| 고동진 | 배우자 | 삼성전자 005930 | 13,433 | 13,683 | +250 | 246 → 234 |
| 고동진 | 차남 | 삼성전자 005930 | 11,853 | 12,073 | +220 | 247 → 235 |

고동진 의원 한 사람이 2026년 삼성전자 본인 보유 집계에 기여하는 값은 **1명·36,000주**다. 세 신고 관계를 모두 더한 61,756주는 이 집계에 쓸 수 없다. 배우자 행에는 브로드컴과 삼성전자가 함께 있고, 2025년에는 보유가 0주로 표시된 종목도 있다. 원자료 행 수로 종목 수나 보유 의원 수를 계산하면 잘못된 결과가 나온다. 두 공개 당시 두 의원의 국민의힘 소속은 당시 보도로 교차 확인했다. 전 의원의 연도별 소속 연결은 별도 작업이다. [2025년 공개 당시 보도](https://www.news1.kr/politics/general-politics/5732817) · [2026년 고동진](https://www.news1.kr/politics/assembly/6114139) · [2026년 안철수](https://www.newsis.com/view/NISX20260325_0003564070)

### 로컬 사용과 갱신

연도별 파일은 [2025년 CSV](./assembly-data/2025-listed-stock-rows.csv)와 [2026년 CSV](./assembly-data/2026-listed-stock-rows.csv)다. UTF-8 BOM CSV이며 공통 열은 `publicYear, referenceDate, publishedOn, sourceRecord, memberName, position, reportedRelationship, reportedListedStocks`다. 마지막 열은 복수 종목, 외국 주식, 0주, 괄호 안 변동량을 그대로 유지한다. 원자료의 직위·증권 분류를 적용한 결과이며 parser가 놓친 신고까지 포함하는지는 미검증이다.

연도별 CSV를 다시 만들 때는 해당 연도 GitHub 원천 CSV 또는 공개 시트의 상세 탭을 CSV로 내려받아 다음과 같이 실행한다. 매년 새 자료의 헤더, 공개일, 기준일을 먼저 확인한다.

```powershell
python tools/import-assembly-stocks.py annual-source.csv docs/assembly-data/2026-listed-stock-rows.csv --published-on 2026-03-26 --reference-date 2025-12-31
```

두 연도 파일을 다시 읽어 위 네 보유 관계의 8개 수량과 증감을 확인했다. 추출기도 두 가지 헤더 형식, 비상장주식·국회사무처 직원 제외, 501개 신고 명세의 CSV 재저장을 검증했다. 전체 서비스 연결에는 국내 종목 마스터 매핑, 명의·의원 식별, 공개 당시 소속 확인이 남아 있다. 공식 [2025년 국회공보](https://www.assembly.go.kr/portal/cnts/cntsCont/dataA.do?cntsDivCd=NAMGZN&menuNo=601019&pdfClsCd=CPR&pdfId=379578)와 [2026년 국회공보](https://www.assembly.go.kr/portal/cnts/cntsCont/dataA.do?cntsDivCd=NAMGZN&menuNo=601019&pdfClsCd=CPR&pdfId=379837)의 상세 페이지가 이번 조회에서 HTTP 400을 반환하여 PDF 표를 직접 대조하지 못했다.

정보공개센터의 2025 공개 시트는 안내 탭에 출처 표시 및 비영리·2차적 저작물 작성 제한을 명시한다. 2026 저장소에서 데이터 재배포 허락은 확인하지 못했다. 내려받은 파일은 로컬 검증용으로 두고 Git 추적에서 제외했다. 공식 자료 이용에는 공직자윤리법 제13조의 목적 외 이용 제한도 적용된다. [2025 공개 시트 안내](https://docs.google.com/spreadsheets/d/182m4MFFj4Ho2cICo3PGy8RyxHZ3vwNklyo5yM4M5M4Q/edit#gid=438404318) · [공직자윤리법 제13조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1031723011)
