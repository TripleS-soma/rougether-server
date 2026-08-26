# Vendored 마케팅 스킬 출처

이 디렉토리의 아래 스킬들은 외부 오픈소스 저장소에서 가져와 프로젝트 스킬로 vendor한 것입니다. 내용을 수정할 일이 생기면 upstream 커밋 기준으로 diff를 확인하고, 로컬 수정 사항은 이 문서에 기록합니다.

## instagram-skills — Instagram 오가닉 콘텐츠

- 스킬: `ig-audience-insights`, `ig-caption-writer`, `ig-carousel-planner`, `ig-content-planner`, `ig-hashtag-strategist`, `ig-hook-extractor`, `ig-humanizer`, `ig-profile-optimizer`, `ig-repurposer`
- 공유 참조 문서: `.claude/references/` (hook-formulas, algorithm-heuristics, hashtag-strategy, media-workflow, voice-profile, voice-rules). 스킬 본문이 `../../references/` 상대 경로로 참조하므로 위치를 옮기지 않습니다.
- 선택 의존성: `.claude/lib/` — Publora(발행)·Apify(오디언스 데이터)·Pixfaro(이미지) 클라이언트. `PUBLORA_API_KEY`, `APIFY_API_TOKEN` 같은 env가 있을 때만 동작하며, 키가 없으면 스킬은 초안 작성까지만 수행합니다. 키는 절대 커밋하지 않습니다.
- 출처: <https://github.com/sergebulaev/instagram-skills> @ `21c4e0b`
- 라이선스: MIT (Copyright (c) 2026 Sergey Bulaev)

## marketingskills — 유료 광고·마케팅 기초

- 스킬:
  - `ads` — Google Ads, Meta(Facebook/Instagram), LinkedIn 등 유료 광고 캠페인 전략·타겟팅·예산·최적화
  - `ad-creative` — 광고 헤드라인·본문·크리에이티브 대량 생성과 반복 개선
  - `ab-testing` — A/B 테스트 설계와 그로스 실험 프로그램
  - `product-marketing` — 포지셔닝·타겟 고객 정의. 다른 마케팅 스킬이 시작 전에 참조하는 기초 문서(`.agents/product-marketing.md`)를 생성하므로 마케팅 작업 첫 단계에서 실행합니다.
- 원본의 `evals/` 디렉토리는 스킬 동작과 무관한 테스트 픽스처라 제외하고 vendor했습니다.
- 출처: <https://github.com/coreyhaines31/marketingskills> @ `becd60e`
- 라이선스: MIT (Copyright (c) 2025 Corey Haines)

## vendor하지 않은 도구

- claude-ads (<https://github.com/AgriciDaniel/claude-ads>): Meta 포함 12개 광고 플랫폼의 감사·모니터링·리포트 스킬. Python 코어(`claude_ads_core`)와 conductor 오케스트레이션에 의존해 스킬 파일만 분리 vendor할 수 없습니다. 필요하면 로컬 CLI에서 `/plugin marketplace add AgriciDaniel/claude-ads` 후 `/plugin install claude-ads@ai-marketing-hub-claude-ads`로 설치합니다.
- Adspirer, Marketing, Postiz (claude.ai 플러그인 카탈로그): claude.ai 계정 단위로 설치하는 플러그인이라 repo에 두지 않습니다. Adspirer가 Meta Ads(Instagram/Facebook) 실계정 연동·집행을 담당합니다.

## MIT 라이선스 전문

MIT 조건에 따라 두 저장소의 저작권 고지와 허가 고지를 아래에 유지합니다.

### instagram-skills

```
MIT License

Copyright (c) 2026 Sergey Bulaev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### marketingskills

```
MIT License

Copyright (c) 2025 Corey Haines

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
