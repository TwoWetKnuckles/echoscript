import { zipSync, strToU8 } from "fflate";
import { jsPDF } from "jspdf";
import { canonical, digest } from "../evidence/index.mjs";
export const REPORT_NOTICE =
  "This report was produced using computer-assisted audio processing and/or transcription. AI-generated transcription requires independent human examiner verification.";
export const xml = (s) =>
  String(s).replace(
    /[&<>"']/g,
    (c) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&apos;",
      })[c],
  );
export function time(s, separator = ".") {
  if (!Number.isFinite(s)) throw Error("Invalid timestamp");
  const sign = s < 0 ? "-" : "";
  const ms = Math.round(Math.abs(s) * 1000);
  return (
    sign +
    [
      Math.floor(ms / 3600000),
      Math.floor(ms / 60000) % 60,
      Math.floor(ms / 1000) % 60,
    ]
      .map((x) => String(x).padStart(2, "0"))
      .join(":") +
    separator +
    String(ms % 1000).padStart(3, "0")
  );
}
function rows(t) {
  return t.segments.map((s) => ({
    start: time(s.start),
    end: time(s.end),
    speaker: s.speaker || "UNASSIGNED",
    text: s.text,
    score: s.confidence
      ? `${s.confidence.value} (${s.confidence.semantics})`
      : "NOT AVAILABLE",
    alternatives: canonical(s.alternatives),
    flags: s.flags.join(";"),
    verification: s.verification,
    provider: t.provider,
    model: t.model,
    transmissionHash: t.transmissionHash,
  }));
}
export function processingHistory(manifest) {
  return [
    "EchoScript processing history",
    `Case: ${manifest.caseId}`,
    `Title: ${manifest.title}`,
    `Software: ${manifest.software.version}`,
    `Generated UTC: ${new Date().toISOString()}`,
    `Source SHA-256: ${manifest.artifacts.find((a) => a.type === "SOURCE")?.sha256}`,
    REPORT_NOTICE,
    "",
    ...manifest.artifacts.flatMap((a) => [
      `Revision ${a.revision}: ${a.id} (${a.type})`,
      `Parents: ${a.parents.join(", ")}`,
      `SHA-256: ${a.sha256}`,
      `Operator: ${a.actor}; UTC: ${a.timestampUtc}`,
      `Algorithm: ${a.operation.algorithm}/${a.operation.version}`,
      `Parameters: ${canonical(a.operation.parameters)}`,
      `Warnings: ${a.warnings.join("; ")}`,
      "",
    ]),
    ...manifest.audit.map(
      (e) =>
        `${e.seq} ${e.timestampUtc} ${e.actor} ${e.action} ${canonical(e.details)} [${e.entryHash}]`,
    ),
    "",
    "Unsigned hash/audit consistency does not independently authenticate origin or legal admissibility.",
  ].join("\n");
}
export function exportTranscript(t, format) {
  const data = rows(t),
    header = Object.keys(
      data[0] || {
        start: "",
        end: "",
        speaker: "",
        text: "",
        score: "",
        alternatives: "",
        flags: "",
        verification: "",
        provider: "",
        model: "",
        transmissionHash: "",
      },
    );
  if (format === "json") return strToU8(JSON.stringify(t, null, 2) + "\n");
  if (format === "txt")
    return strToU8(
      [
        REPORT_NOTICE,
        `State: ${t.state}; provider: ${t.provider}; model: ${t.model}`,
        `Transmission SHA-256: ${t.transmissionHash}`,
        "",
        ...data.flatMap((r) => [
          `${r.start}–${r.end} ${r.speaker} [${r.verification}]`,
          r.text,
          `Score: ${r.score}; alternatives: ${r.alternatives}; flags: ${r.flags}`,
          "",
        ]),
      ].join("\n"),
    );
  if (format === "csv") {
    const cell = (x) =>
      '"' +
      String(x)
        .replace(/^[\s]*[=+@-]/, (m) => "'" + m)
        .replaceAll('"', '""') +
      '"';
    return strToU8(
      [header, ...data.map((r) => header.map((k) => r[k]))]
        .map((r) => r.map(cell).join(","))
        .join("\r\n") + "\r\n",
    );
  }
  if (format === "html")
    return strToU8(
      `<!doctype html><html lang="en"><meta charset="utf-8"><title>EchoScript transcript</title><h1>Transcript</h1><p>${xml(REPORT_NOTICE)}</p><p>${xml(t.state)} · ${xml(t.provider)} · ${xml(t.model)}</p><table><thead><tr>${header.map((k) => `<th>${xml(k)}</th>`).join("")}</tr></thead><tbody>${data.map((r) => `<tr>${header.map((k) => `<td>${xml(r[k])}</td>`).join("")}</tr>`).join("")}</tbody></table></html>`,
    );
  if (format === "srt" || format === "vtt") {
    if (
      t.segments.some(
        (s) =>
          s.start < 0 ||
          s.end <= s.start ||
          s.flags.includes("OUT_OF_RANGE") ||
          s.flags.includes("TIMESTAMP_ANOMALY"),
      )
    )
      throw Error(
        "Subtitle export requires examiner-corrected valid timestamps; original anomalies remain in JSON",
      );
    const vtt = format === "vtt";
    return strToU8(
      (vtt ? "WEBVTT\n\n" : "") +
        t.segments
          .map(
            (s, i) =>
              `${i + 1}\n${time(s.start, vtt ? "." : ",")} --> ${time(s.end, vtt ? "." : ",")}\n${s.speaker ? s.speaker + ": " : ""}${s.text.replaceAll("-->", "→")}\n`,
          )
          .join("\n"),
    );
  }
  if (format === "ctm") {
    if (
      t.segments.some(
        (s) =>
          s.flags.includes("OUT_OF_RANGE") ||
          s.flags.includes("TIMESTAMP_ANOMALY"),
      )
    )
      throw Error(
        "CTM requires valid provider word alignment without timestamp anomalies",
      );
    if (t.segments.some((s) => s.text.trim() && !s.words?.length))
      throw Error(
        "CTM requires provider-supported word alignment for every exported segment",
      );
    return strToU8(
      t.segments
        .flatMap((s) =>
          s.words.map(
            (w) =>
              `echoscript 1 ${w.start.toFixed(3)} ${(w.end - w.start).toFixed(3)} ${w.text}${w.confidence ? " " + w.confidence.value : ""}`,
          ),
        )
        .join("\n") + "\n",
    );
  }
  if (format === "docx") {
    const paragraphs = [
      REPORT_NOTICE,
      `State: ${t.state}`,
      `Provider/model: ${t.provider}/${t.model}`,
      `Transmission SHA-256: ${t.transmissionHash}`,
      ...data.map((r) =>
        Object.entries(r)
          .map(([k, v]) => `${k}: ${v}`)
          .join("\n"),
      ),
    ];
    const document = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraphs
      .map(
        (p) =>
          `<w:p>${p
            .split("\n")
            .map(
              (s) =>
                `<w:r><w:t xml:space="preserve">${xml(s)}</w:t></w:r><w:r><w:br/></w:r>`,
            )
            .join("")}</w:p>`,
      )
      .join(
        "",
      )}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`;
    return zipSync({
      "[Content_Types].xml": strToU8(
        '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
      ),
      "_rels/.rels": strToU8(
        '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
      ),
      "word/document.xml": strToU8(document),
    });
  }
  if (format === "xlsx") {
    const column = (i) => {
      let s = "";
      for (let x = i + 1; x > 0; x = Math.floor((x - 1) / 26))
        s = String.fromCharCode(65 + ((x - 1) % 26)) + s;
      return s;
    };
    const sheet = `<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${[header, ...data.map((r) => header.map((k) => r[k]))].map((row, i) => `<row r="${i + 1}">${row.map((v, j) => `<c r="${column(j)}${i + 1}" t="inlineStr"><is><t xml:space="preserve">${xml(v)}</t></is></c>`).join("")}</row>`).join("")}</sheetData></worksheet>`;
    return zipSync({
      "[Content_Types].xml": strToU8(
        '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
      ),
      "_rels/.rels": strToU8(
        '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      ),
      "xl/workbook.xml": strToU8(
        '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Transcript" sheetId="1" r:id="rId1"/></sheets></workbook>',
      ),
      "xl/_rels/workbook.xml.rels": strToU8(
        '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
      ),
      "xl/worksheets/sheet1.xml": strToU8(sheet),
    });
  }
  throw Error("Unsupported document format");
}
export function exportPdf(manifest, transcript, fontBase64) {
  if (!fontBase64) throw Error("The bundled Unicode font is required");
  const pdf = new jsPDF({ unit: "mm", format: "a4", compress: true });
  pdf.addFileToVFS("NotoSans-Regular.ttf", fontBase64);
  pdf.addFont("NotoSans-Regular.ttf", "NotoSans", "normal");
  pdf.setFont("NotoSans");
  pdf.setFontSize(9);
  const text = [
    processingHistory(manifest),
    "",
    transcript
      ? new TextDecoder().decode(exportTranscript(transcript, "txt"))
      : "No transcript supplied.",
  ].join("\n");
  let y = 15;
  for (const paragraph of text.split("\n"))
    for (const line of pdf.splitTextToSize(paragraph || " ", 180)) {
      if (y > 282) {
        pdf.addPage();
        y = 15;
      }
      pdf.text(line, 15, y);
      y += 4.8;
    }
  pdf.setProperties({
    title: "EchoScript processing report",
    subject: "Computer-assisted audio processing; examiner review required",
    creator: "EchoScript",
  });
  return new Uint8Array(pdf.output("arraybuffer"));
}
export function checksumSidecar(files) {
  return strToU8(
    Object.entries(files)
      .map(([name, bytes]) => `${digest(bytes)}  ${name}`)
      .join("\n") + "\n",
  );
}
