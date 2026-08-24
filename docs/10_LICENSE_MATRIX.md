# License matrix and clean-room policy

This is an engineering audit, not legal advice. Recheck licenses and obtain legal review before distribution.

## Repository matrix

| Source | Inspected revision | License evidence | Components of interest | Reuse decision | Obligations/risk |
|---|---|---|---|---|---|
| [nikit6000/QDPlay](https://github.com/nikit6000/QDPlay) | `develop` / `b6464e9c05fe690a83b1d6c1d66227059da30ee1` | Top-level GNU GPL v3 text | QDLink framing, handshake, H.264, touch, lifecycle | **Architecture/protocol facts only** unless product intentionally adopts GPL-3.0 | Copying/adapting covered code into a distributed combined app generally requires GPL-compatible licensing, source availability, notices, and license terms |
| QDPlay bundled cJSON | Same tree | MIT notice in `cJSON.c/.h` | JSON parser | Prefer Android/Kotlin JSON instead; separately reusable under MIT if needed | Preserve copyright and permission notice |
| [cbtham/carplayx50](https://github.com/cbtham/carplayx50) | `main` / `57999a92d2d439db9073ba0b286100c37463cee7` | **No license found** | Community topology/behavior narrative | Ideas/facts only; do not copy text/assets/code | Public visibility is not a reuse grant; default copyright applies |
| [mrmees/open-android-auto](https://github.com/mrmees/open-android-auto) | `main` / `61eab61c5f9968154ff1a80faa8c0a427b208479` | GPL-3.0 | Modern AA protocol/lifecycle research | Architecture only for this QDLink product | Direct reuse triggers GPL obligations; also unrelated protocol risk |
| [f1xpl/aasdk](https://github.com/f1xpl/aasdk) | `development` / `046b3b381595509d0939fa84b14a90978f46ff63` | README states GPLv3 | AOAP, transport, SSL, channels | Architecture only; no need in QDLink MVP | Static/dynamic incorporation into distributed product requires GPL-compatible treatment; dependency licenses also apply |
| [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) | `main` / `048f4eaf663cbb9d9589ebd9c4233d4a262759a3` | AGPL-3.0 top-level license | Android lifecycle, rendering, touch, reconnect | Architecture/testing ideas only | Strong copyleft including AGPL network-interaction provisions; avoid source copying without deliberate licensing decision |
| [mossyhub/openautolink](https://github.com/mossyhub/openautolink) | `main` / `714704242fa33046953a972a48c556e76eb0a995` | `THIRD_PARTY_LICENSES.md`: GPL-3.0-or-later; top-level README says TBD | Kotlin/JNI split, transport ownership, video/touch/reconnect | Architecture only | Repository has an internal license-description inconsistency; treat as GPL-3.0-or-later unless clarified. Statically linked aasdk makes combined work GPL per its own notice |

## Official/proprietary sources

| Source | Rights/status | Permitted Phase-0 use | Prohibited/default handling |
|---|---|---|---|
| Official QDLink Google Play listing | Copyrighted listing; factual product declarations | Cite and summarize interoperability behavior | Do not copy proprietary artwork or imply affiliation |
| Official QDLink APK 1.9.7 (107) | Proprietary; lawfully pulled from the user's Google Play installation for Phase 0.25 | Static/dynamic analysis for interoperability facts with provenance and hashes recorded in `15_OFFICIAL_APK_ANALYSIS.md` | Do not commit or redistribute APKs, decompiled code, native libraries, assets, credentials, or large verbatim excerpts |
| Geely GE13 manual | Copyrighted manufacturer documentation | Cite relevant facts and small necessary descriptions | Do not bundle/reproduce the manual or screenshots without permission |
| Target USB/protocol captures | User-generated interoperability evidence; may contain identifiers/data | Store securely, sanitize, derive byte-level specifications | Do not publish personal data, phone identifiers, contacts/messages, or unrelated traffic |

## Android and AOSP sources

Android developer documentation and samples have their own content/code licenses; AOSP framework source is generally Apache-2.0 with file-level notices. Public API behavior and protocol facts may be implemented independently. Any copied sample/source must retain the applicable notices and attribution. The project should write its own minimal manifest, filter, service, and stream code rather than paste large samples.

## Protocol facts versus expressive code

The clean-room design may use facts necessary for interoperability, such as:

- markers and numeric message IDs.
- field order, length, byte order, CRC algorithm name.
- literal command/app/logic identifiers required on the wire.
- state transitions required by the receiver.
- observed H.264 and touch semantics.

Avoid copying QDPlay's function structure, comments, variable names, control flow, error handling, or source blocks. The Phase-0 documents form a behavior-level specification from which Phase 1 can be independently implemented.

## Recommended clean-room workflow

1. Freeze the source audit revision and keep citations to public commits.
2. Implement from `02_QDLINK_PROTOCOL_MAP.md`, not with QDPlay source open during routine coding.
3. Create original Kotlin models, serializers, and state-machine organization.
4. Derive golden vectors independently from documented fields; compare bytes against captures/QDPlay only in tests.
5. Record the origin/evidence label for each newly discovered constant.
6. Do not import GPL files into the production tree.
7. Review the dependency graph before every release for transitive copyleft/native libraries.
8. Keep proprietary APK artifacts, if later obtained, outside version control and never distribute them.

## If direct QDPlay reuse is later chosen

That is a product licensing decision, not a technical shortcut. Before copying:

- Decide that the application can be distributed under GPL-3.0-compatible terms.
- Preserve notices and the full license.
- Provide corresponding source and installation/build information as required.
- Audit every linked library and Android distribution channel requirement.
- Document modifications.
- Confirm whether any app-store policies conflict with the intended GPL distribution model.

JNI does not isolate licensing automatically. A native GPL core linked into the APK can make the distributed combined work subject to GPL obligations.

## Current decision

Use QDPlay and the Android Auto projects as source evidence and architectural references only. Implement the Phase-1 QDLink core cleanly in Kotlin/Java under a project license chosen before coding. Reassess only if a protocol gap is impossible to close through captures and independently written code.
