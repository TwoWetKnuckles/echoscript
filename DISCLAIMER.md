# Responsibility and disclaimer notice

EchoScript is designed to support defensible forensic-audio analysis, transcription, evidence preservation, and chain-of-custody workflows. It is a technical tool under development, not a legal determination, certified forensic service or substitute for a qualified examiner. **BETA GATE: NOT PASSED. Evaluate the current development build with synthetic, non-sensitive material.** See the [executed QA matrix and outstanding work](docs/qa/STATUS.md).

## Software license, warranty and liability

Original project code is licensed under [Apache License 2.0](LICENSE). Sections 7 and 8 provide the operative warranty disclaimer and limitation of contributor liability, subject to applicable law and written agreements. The software and contributions are supplied **AS IS, without warranties or conditions**, except where applicable law requires otherwise or an authorized written agreement provides otherwise. Users are responsible for determining suitability and the risks of their use or redistribution.

The project makes no warranty of transcription accuracy, restoration accuracy, evidence integrity under every circumstance, security, availability, fitness for a particular investigation, regulatory approval or legal outcome. Errors, omissions, vulnerabilities, data loss, key loss, device failure and service interruptions remain possible. Passing a documented test does not establish that defects are impossible.

**No notice can guarantee that developers, maintainers or other contributors will have zero liability in every jurisdiction.** Nothing here excludes a liability or right that applicable law does not allow to be excluded, overrides a separately authorized written agreement, or enlarges the limitation provided by the license. This notice explains project boundaries; it does not amend the license, impose an additional license condition, create an indemnity obligation, or purport to create a separate contract through reading it or using the software.

## Examiner and end-user responsibilities

For **each individual case**, the examiner and the organization directing the work are responsible for determining and implementing the requirements that apply to their activities, including:

- **Authority and jurisdiction:** lawful acquisition, recording, interception, access, consent, warrants or other authority where required; applicable privacy, surveillance, professional, agency and court rules. An import button or submission approval supplies no legal authority.
- **Custody and preservation:** independent preservation of original acquired bytes and acquisition context; documented handlers and transfers; trusted external hash checkpoints; secure devices, keys, copies and export destinations; recovery planning and verification after transfer. The application's records supplement organizational custody procedures.
- **Case-specific information duties:** confidentiality, privilege, disclosure and discovery obligations, data minimization, authorized access, residency, retention, legal holds and permitted destruction. Do not delete material subject to an applicable hold or preservation obligation.
- **Technical validation:** evaluation of the exact build, device, codecs, algorithms, provider configuration and workflow before reliance; original/processed comparison; investigation of warnings or failed verification. Stop relying on an affected result when a defect could change it, preserve the original and failure record, and obtain appropriate review.
- **Human examination:** independent listening and review of timestamps, speaker labels, words, scores and all uncertain segments. Speech detection is a candidate estimate; restoration reconstructs estimates rather than lost original information. Provider scores are not necessarily calibrated probabilities. Human verification requires an actual examiner action and does not become a legal authentication merely because it has a label.
- **Accurate presentation:** identify originals, derivatives, reconstructed material and human edits separately; disclose processing, software/provider versions, material limitations, uncertainty and verification status. Preserve the processing history. Do not describe the tool's output as an unmodified original or an automated result as independently verified.
- **External processing:** decide whether information may leave a device and obtain the necessary authority and agreements before transmission. Qualify the exact provider, region, retention/deletion, training use, subprocessors and contractual controls; recheck changes. In-app consent is not a substitute for those obligations. A provider's certification does not certify this application or your deployment.

These responsibilities apply to investigators, lawyers, law-enforcement personnel and other users according to their actual roles. They are not resolved by a single global preset. Obtain advice from qualified counsel and appropriate forensic/security specialists for the relevant jurisdiction, organization and matter. Project documentation is general technical information, not legal advice.

## Deployers, redistributors and support

An organization operating or redistributing EchoScript is responsible for the services and promises it supplies, including access control, configuration, updates, incident handling, notices, applicable agreements and a support/retention plan. Publication of this source does not itself provide managed hosting, a service-level agreement, professional supervision or a promise of continued maintenance. Do not promise support, warranties or indemnity on another contributor's behalf; Apache section 9 addresses additional obligations a redistributor chooses to accept.

Making the source available does not, by itself, appoint contributors as evidence custodians, examiners, legal advisers or service operators for another party's deployment. Actual roles and responsibilities depend on conduct, applicable law and any authorized agreements; labels in this document do not decide them.

## Technical verification and legal conclusions

A matching SHA-256 establishes consistency with the compared bytes or trusted checkpoint. An internally consistent provenance graph or audit chain does not prove that the recording is authentic, complete, lawfully acquired or attributed to the correct person. An attacker able to replace an entire unsigned package may replace its hashes and chain together. Opt-in device-key checkpoints authenticate stated historical prefixes; examiner identity, independent signer trust and trusted timestamping are not supplied by those checkpoints. See the [implemented signature boundaries](docs/evidence/PROTECTED-TRANSFER.md).

EchoScript does not itself make evidence admissible, court-admissible or legally authenticated. No NIST, CJIS, FIPS, FedRAMP, HIPAA or other certification/compliance claim is made for the application or a deployment. Technical controls, organizational procedure, provider qualifications, deployment assessment and legal admissibility are separate questions. Their determination remains with the responsible organizations, qualified professionals and competent authorities under the applicable rules.

See [security reporting](SECURITY.md), [known trust boundaries](docs/security/THREAT_MODEL.md), [operator guidance](docs/security/OPERATIONS.md) and [licensing](docs/legal/LICENSING.md). Disclaimers never substitute for correcting defects or preserving evidence-integrity safeguards.
