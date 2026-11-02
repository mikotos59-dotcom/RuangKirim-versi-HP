// Tampilan Ruang Kirim (sama untuk PC dan HP). Data dari window.RK (preload.js di PC, RKNative di HP).
"use strict";

// ---------- jembatan versi HP ----------
if (!window.RK && window.RKNative) {
  const pend = new Map(), cbs = [];
  let seq = 0;
  window.RK = {
    platform: "hp",
    call: (m, ...a) => new Promise((res, rej) => { const id = ++seq; pend.set(id, { res, rej }); window.RKNative.call(id, m, JSON.stringify(a)); }),
    onState: (cb) => cbs.push(cb),
    pathOf: () => "",
  };
  window.__rkRes = (id, ok, v) => { const p = pend.get(id); pend.delete(id); if (p) ok ? p.res(v) : p.rej(new Error(v)); };
  window.__rkState = (s) => { for (const f of cbs) f(s); };
}

(() => {
  const $ = (s) => document.querySelector(s);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const HP = RK.platform === "hp";
  let S = { me: {}, settings: {}, devices: [], transfers: [], prompts: [] };
  let shownPrompt = null, modalKind = null;

  const size = (b) => { b = +b || 0; if (b < 1024) return b + " B"; const u = ["KB", "MB", "GB", "TB"]; let i = -1; do { b /= 1024; i++; } while (b >= 1024 && i < 3); return (b >= 100 ? b.toFixed(0) : b.toFixed(1)).replace(".", ",") + " " + u[i]; };
  const dur = (s) => { s = Math.max(0, Math.round(s)); return s < 60 ? s + " dtk" : s < 3600 ? Math.floor(s / 60) + " mnt " + (s % 60) + " dtk" : Math.floor(s / 3600) + " jam " + Math.floor((s % 3600) / 60) + " mnt"; };
  const ICON = {
    pc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>',
    hp: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>',
    up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M11 6l-6 6 6 6"/></svg>',
    ok: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/></svg>',
    warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l9.5 17h-19z"/><path d="M12 10v4M12 17.5v.01"/></svg>',
  };

  let toastT = null;
  function toast(msg, ms = 3200) {
    const t = $("#toast");
    t.textContent = msg; t.hidden = false;
    clearTimeout(toastT); toastT = setTimeout(() => (t.hidden = true), ms);
  }
  const run = (p, okMsg) => Promise.resolve(p).then((v) => { if (okMsg) toast(typeof okMsg === "function" ? okMsg(v) : okMsg); return v; }, (e) => { toast(e.message || String(e), 5000); });

  // ---------- kepala ----------
  function renderMe() {
    const m = S.me;
    const ip = (m.ips || [])[0];
    $("#me").innerHTML = `<span class="dot"></span>Siap menerima sebagai <b id="nm" title="Ganti nama">${esc(m.name)}</b>${ip ? " · " + esc(ip) : " · belum tersambung WiFi"}`;
    $("#nm").onclick = renameDialog;
  }

  // ---------- perangkat ----------
  function renderDevices() {
    const list = S.devices;
    const box = $("#devs");
    if (!list.length) {
      box.innerHTML = `<div class="empty"><b>Belum ada perangkat</b>Buka Ruang Kirim di ${HP ? "PC" : "HP atau PC lain"} yang tersambung ke WiFi yang sama.${HP ? " Atau tekan <b style='display:inline;font:inherit'>Pindai QR di PC</b>." : " Untuk HP, tekan <b style='display:inline;font:inherit'>Kode QR untuk HP</b>."}</div>`;
    } else {
      box.innerHTML = list.map((d) => `
        <div class="dev ${d.online ? "" : "off"}" data-id="${esc(d.id)}">
          <div class="dhead">
            <div class="dico">${ICON[d.type] || ICON.pc}</div>
            <div class="dname"><b>${esc(d.name)}</b><small>${d.online ? "Aktif" : "Tidak aktif"}${d.ip ? " · " + esc(d.ip) : ""}</small></div>
            <span class="tag ${d.known ? "k" : "u"}">${d.known ? "Dikenal" : "Belum dikenal"}</span>
          </div>
          <div class="dact">
            <button class="btn pri" data-a="send" ${d.online || d.known ? "" : "disabled"}>${ICON.up}${S.pending ? "Kirim ke sini" : "Kirim file"}</button>
            ${HP || S.pending ? "" : `<button class="btn" data-a="folder" ${d.online || d.known ? "" : "disabled"}>Kirim folder</button>`}
            ${d.known ? `<button class="btn" data-a="forget" title="Lupakan perangkat ini">Lupakan</button>` : `<button class="btn" data-a="pair" ${d.online ? "" : "disabled"}>Pasangkan</button>`}
          </div>
        </div>`).join("");
    }
    $("#devHint").textContent = list.length && !HP ? "Bisa juga seret file dari Explorer ke kartu perangkat." : list.length ? "" : "Tidak muncul? Pastikan kedua perangkat di WiFi yang sama, lalu izinkan Ruang Kirim saat Windows Firewall bertanya (jaringan Pribadi). Atau pakai Tambah lewat IP.";
  }
  $("#devs").addEventListener("click", (e) => {
    const b = e.target.closest("[data-a]");
    if (!b) return;
    const id = b.closest(".dev").dataset.id;
    const d = S.devices.find((x) => x.id === id);
    const a = b.dataset.a;
    if (a === "send") {
      if (S.pending) run(RK.call("sendPending", id));
      else run(RK.call("send", id));
    } else if (a === "folder") run(RK.call("sendFolder", id));
    else if (a === "pair") run(RK.call("pair", id), (n) => `Terpasang dengan ${n}. Sekarang perangkat ini dikenal.`);
    else if (a === "forget") confirmBox(`Lupakan ${d ? d.name : "perangkat ini"}?`, "Kiriman berikutnya dari perangkat ini akan ditandai Belum dikenal, dan perlu dipasangkan lagi.", "Lupakan", () => RK.call("forget", id));
  });

  // seret file ke kartu perangkat (PC)
  if (!HP) {
    let depth = 0;
    const has = (e) => [...(e.dataTransfer?.types || [])].includes("Files");
    window.addEventListener("dragenter", (e) => { if (!has(e)) return; depth++; $("#dropall").hidden = false; });
    window.addEventListener("dragleave", () => { if (--depth <= 0) { depth = 0; $("#dropall").hidden = true; } });
    window.addEventListener("dragover", (e) => {
      if (!has(e)) return;
      e.preventDefault();
      document.querySelectorAll(".dev.drop").forEach((x) => x.classList.remove("drop"));
      const card = e.target.closest?.(".dev:not(.off)");
      if (card) card.classList.add("drop");
      e.dataTransfer.dropEffect = card ? "copy" : "none";
    });
    window.addEventListener("drop", (e) => {
      e.preventDefault();
      depth = 0; $("#dropall").hidden = true;
      document.querySelectorAll(".dev.drop").forEach((x) => x.classList.remove("drop"));
      const card = e.target.closest?.(".dev:not(.off)");
      if (!card) return;
      const paths = [...e.dataTransfer.files].map((f) => RK.pathOf(f)).filter(Boolean);
      if (paths.length) run(RK.call("send", card.dataset.id, paths));
    });
  }

  // ---------- kiriman ----------
  const STAT = { menyambung: "Menyambung…", menunggu: "Menunggu jawaban…", jalan: "", selesai: "Selesai", ditolak: "Ditolak", gagal: "Gagal", batal: "Dibatalkan" };
  function renderTransfers() {
    const L = S.transfers;
    $("#bClear").hidden = !L.some((t) => !["menyambung", "menunggu", "jalan"].includes(t.status));
    if (!L.length) { $("#tlist").innerHTML = `<div class="empty"><b>Belum ada kiriman</b>File yang dikirim dan diterima akan muncul di sini.</div>`; return; }
    $("#tlist").innerHTML = L.map((t) => {
      const pct = t.total ? Math.min(100, (t.done / t.total) * 100) : t.status === "selesai" ? 100 : 0;
      const first = t.files[0] ? t.files[0].n.split("/").pop() : "";
      const title = t.count > 1 ? `${esc(first)} +${t.count - 1} file` : esc(first);
      const who = (t.dir === "kirim" ? "ke " : "dari ") + esc(t.peer.name) + (t.known ? "" : " (belum dikenal)");
      let s = STAT[t.status] || t.status;
      if (t.status === "jalan") {
        const left = t.speed > 0 ? (t.total - t.done) / t.speed : 0;
        s = `${size(t.done)} / ${size(t.total)} · ${size(t.speed)}/dtk${left ? " · sisa " + dur(left) : ""}`;
      } else if (t.status === "selesai") s = `Selesai · ${size(t.total)}`;
      if (t.err && t.status !== "selesai") s += " · " + esc(t.err);
      const live = ["menyambung", "menunggu", "jalan"].includes(t.status);
      return `<div class="tr st-${t.status}" data-t="${esc(t.id)}">
        <div class="tdir">${t.dir === "kirim" ? ICON.up : ICON.down}</div>
        <div class="tt"><b>${title}</b><small>${who}</small></div>
        <div class="tbtn">${live ? `<button class="btn sm dan" data-a="cancel">Batal</button>` : t.dir === "terima" && t.status === "selesai" && t.saved.length ? `<button class="btn sm" data-a="open">${HP ? "Buka" : "Tampilkan"}</button>` : ""}</div>
        <div class="tt" style="grid-column:2"><div class="prog"><i style="width:${pct.toFixed(1)}%"></i></div><small class="s">${s}</small></div>
      </div>`;
    }).join("");
  }
  $("#tlist").addEventListener("click", (e) => {
    const b = e.target.closest("[data-a]");
    if (!b) return;
    const tid = b.closest(".tr").dataset.t;
    if (b.dataset.a === "cancel") RK.call("cancel", tid);
    else if (b.dataset.a === "open") run(RK.call("open", tid, 0));
  });
  $("#bClear").onclick = () => RK.call("clearDone");
  $("#bDir").onclick = () => run(RK.call("openDir"));

  // ---------- jendela ----------
  const modal = $("#modal"), box = $("#box");
  function openModal(kind, html, onclick) {
    modalKind = kind;
    box.innerHTML = html;
    box.onclick = onclick || null;
    modal.hidden = false;
  }
  function closeModal() { modal.hidden = true; box.innerHTML = ""; modalKind = null; shownPrompt = null; clearInterval(timerIv); }
  modal.addEventListener("click", (e) => { if (e.target === modal && !shownPrompt) closeModal(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !modal.hidden && !shownPrompt) closeModal(); });

  function confirmBox(title, msg, ok, fn) {
    openModal("confirm", `<h3>${esc(title)}</h3><p class="muted">${esc(msg)}</p><div class="acts"><button class="btn" data-x>Batal</button><button class="btn pri" data-ok>${esc(ok)}</button></div>`, (e) => {
      if (e.target.closest("[data-x]")) closeModal();
      if (e.target.closest("[data-ok]")) { closeModal(); run(fn()); }
    });
  }
  function inputBox(title, msg, attrs, ok, fn, area) {
    openModal("input", `<h3>${esc(title)}</h3><p class="muted">${msg}</p>${area ? `<textarea class="field" id="inp" ${attrs}></textarea>` : `<input class="field" id="inp" ${attrs}>`}<div class="acts"><button class="btn" data-x>Batal</button><button class="btn pri" data-ok>${esc(ok)}</button></div>`, (e) => {
      if (e.target.closest("[data-x]")) closeModal();
      if (e.target.closest("[data-ok]")) { const v = $("#inp").value; closeModal(); fn(v); }
    });
    const i = $("#inp");
    i.focus();
    if (!area) i.onkeydown = (e) => { if (e.key === "Enter") box.querySelector("[data-ok]").click(); };
  }
  function renameDialog() {
    inputBox("Nama perangkat ini", "Nama ini yang terlihat di perangkat lain.", `maxlength="40" value="${esc(S.me.name)}"`, "Simpan", (v) => run(RK.call("rename", v)));
  }

  // ---------- permintaan Ya/Tidak dari perangkat lain ----------
  let timerIv = null;
  function trustBlock(f) {
    return f.known
      ? `<div class="trust k">${ICON.ok}<div><b>Perangkat dikenal</b><small>Sudah dipasangkan sebelumnya · ${esc(f.fp)}</small></div></div>`
      : `<div class="trust u">${ICON.warn}<div><b>Belum dikenal</b><small>Perangkat ini belum pernah dipasangkan. Terima hanya kalau kamu yakin ini perangkatmu atau orang yang kamu kenal. · ${esc(f.fp)}</small></div></div>`;
  }
  function showPrompt(p) {
    shownPrompt = p.rid;
    const f = p.from;
    let html;
    if (p.kind === "kirim") {
      html = `<h3>${esc(f.name)} mau mengirim ${p.count} file</h3><p class="muted">${esc(f.type === "hp" ? "HP" : "PC")} · ${esc(f.ip || "")} · total ${size(p.total)}</p>
        ${trustBlock(f)}
        <ul class="files">${p.files.map((x) => `<li><span>${esc(x.n)}</span><em>${size(x.s)}</em></li>`).join("")}${p.count > p.files.length ? `<li><span>… dan ${p.count - p.files.length} file lainnya</span></li>` : ""}</ul>
        <div class="timer"><i id="tbar"></i></div>
        <div class="acts"><button class="btn dan" data-no>Tolak</button><button class="btn pri" data-yes>Terima</button></div>`;
    } else if (p.kind === "pasang") {
      html = `<h3>Pasangkan dengan ${esc(f.name)}?</h3>
        <p class="muted">Pastikan kode ini <b>sama persis</b> dengan yang tampil di ${esc(f.name)}. Kalau beda, tolak.</p>
        <div class="code">${esc(p.code.slice(0, 3))} ${esc(p.code.slice(3))}</div>
        ${trustBlock(f)}
        <div class="timer"><i id="tbar"></i></div>
        <div class="acts"><button class="btn dan" data-no>Tolak</button><button class="btn pri" data-yes>Kodenya sama, pasangkan</button></div>`;
    } else {
      html = `<h3>Pasangkan dengan ${esc(f.name)}</h3>
        <p class="muted">Cocokkan kode ini dengan yang muncul di ${esc(f.name)}, lalu setujui di sana.</p>
        <div class="code">${esc(p.code.slice(0, 3))} ${esc(p.code.slice(3))}</div>
        <p class="muted">Menunggu konfirmasi…</p>
        <div class="acts"><button class="btn" data-no>Batal</button></div>`;
    }
    openModal("prompt", html, (e) => {
      if (e.target.closest("[data-yes]")) { RK.call("respond", p.rid, true); closeModal(); }
      if (e.target.closest("[data-no]")) { RK.call("respond", p.rid, false); closeModal(); }
    });
    clearInterval(timerIv);
    if (p.expires) {
      const total = p.expires - Date.now();
      timerIv = setInterval(() => { const b = $("#tbar"); if (!b) return clearInterval(timerIv); b.style.width = Math.max(0, ((p.expires - Date.now()) / total) * 100) + "%"; }, 250);
    }
  }
  function syncPrompts() {
    const ps = S.prompts || [];
    if (shownPrompt && !ps.some((p) => p.rid === shownPrompt)) closeModal(); // dijawab otomatis / kedaluwarsa / dibatalkan
    if (!shownPrompt && (modal.hidden || modalKind === "qr")) {
      // permintaan masuk didahulukan daripada "menunggu" milik sendiri
      const p = ps.find((x) => x.kind !== "tunggu") || ps[0];
      if (p) showPrompt(p);
    }
  }

  // ---------- pasangkan ----------
  let qrIv = null;
  async function showQr() {
    let q;
    try { q = await RK.call("qr"); } catch (e) { return toast(e.message); }
    openModal("qr", `<h3>Pasangkan HP</h3>
      <p class="muted">Di HP, buka Ruang Kirim lalu tekan <b>Pindai QR di PC</b>. HP dan PC harus di WiFi yang sama.</p>
      <div class="qr"><img src="${q.img}" alt="Kode QR"></div>
      <p class="muted" id="qrExp"></p>
      <div class="acts"><button class="btn" data-copy>Salin kode (untuk PC lain)</button><button class="btn pri" data-x>Tutup</button></div>`, (e) => {
      if (e.target.closest("[data-x]")) { clearInterval(qrIv); closeModal(); }
      if (e.target.closest("[data-copy]")) run(RK.call("copyCode"), "Kode disalin. Di PC lain: Tempel kode.");
    });
    clearInterval(qrIv);
    const known0 = S.devices.filter((d) => d.known).length;
    qrIv = setInterval(() => {
      if (modalKind !== "qr") return clearInterval(qrIv);
      const left = Math.round((q.expires - Date.now()) / 1000);
      if (left <= 0) { clearInterval(qrIv); showQr(); return; }
      $("#qrExp").textContent = `Kode sekali pakai, berganti dalam ${dur(left)}.`;
      if (S.devices.filter((d) => d.known).length > known0) { clearInterval(qrIv); closeModal(); toast("HP terpasang. Sekarang perangkat itu dikenal."); }
    }, 500);
  }
  $("#bQr").onclick = showQr;
  $("#bScan").onclick = () => run(RK.call("scanQr"), (n) => n ? `Terpasang dengan ${n}.` : "");
  $("#bCode").onclick = () => inputBox("Tempel kode perangkat", "Kode diawali <b>RK1:</b>. Di PC lain: Kode QR untuk HP → Salin kode.", 'placeholder="RK1:…" spellcheck="false"', "Pasangkan", (v) => run(RK.call("pairCode", v.trim()), (n) => `Terpasang dengan ${n}.`), true);
  $("#bIp").onclick = () => inputBox("Tambah lewat alamat IP", `Kalau perangkat tidak muncul sendiri (sebagian router memblok pencarian). Lihat alamat IP di bagian atas Ruang Kirim perangkat itu.`, 'placeholder="192.168.1.7" inputmode="decimal"', "Tambah", (v) => run(RK.call("addIp", v), (n) => `${n} ditemukan.`));
  $("#bScanW").onclick = () => { RK.call("rescan"); toast("Mencari perangkat di WiFi ini…", 1500); };

  // ---------- kiriman dari aplikasi lain (HP: Bagikan) ----------
  function renderPending() {
    const p = S.pending;
    $("#pending").hidden = !p;
    if (p) $("#pendTxt").textContent = `${p.count} file (${size(p.total)}) siap dikirim. Pilih perangkat tujuan.`;
  }
  $("#pendX").onclick = () => RK.call("clearPending");

  // ---------- pengaturan ----------
  function openSettings() {
    const s = S.settings, m = S.me;
    const sw = (k) => `<button class="sw ${s[k] ? "on" : ""}" data-sw="${k}" aria-pressed="${!!s[k]}"></button>`;
    openModal("set", `<h3>Pengaturan</h3>
      <div class="row"><div class="lab">Nama perangkat ini<small>${esc(m.name)}</small></div><button class="btn sm" data-a="rename">Ganti</button></div>
      <div class="row"><div class="lab">Folder file yang diterima<small>${esc(s.saveDir)}</small></div>${HP ? "" : '<button class="btn sm" data-a="dir">Ganti</button>'}</div>
      <div class="row"><div class="lab">Terima otomatis dari perangkat dikenal<small>Tanpa tanya Ya/Tidak untuk perangkat yang sudah dipasangkan.</small></div>${sw("autoKnown")}</div>
      <div class="row"><div class="lab">Hanya terima dari perangkat dikenal<small>Kiriman dari perangkat yang belum dipasangkan langsung ditolak.</small></div>${sw("onlyKnown")}</div>
      ${HP ? "" : `<div class="row"><div class="lab">Tetap berjalan di tray saat jendela ditutup<small>Supaya tetap bisa menerima file.</small></div>${sw("tray")}</div>`}
      <div class="row"><div class="lab">Kode perangkat ini<small>${esc(m.fp)} · port ${esc(m.port)} · ${esc((m.ips || []).join(", ") || "belum tersambung")}</small></div></div>
      <p class="muted">Semua file dienkripsi ujung ke ujung (ECDH P-256 + AES-256-GCM) dan hanya lewat WiFi yang sama, tidak lewat internet.${HP ? "" : " Kalau perangkat lain tidak bisa menemukan PC ini, izinkan Ruang Kirim di Windows Firewall untuk jaringan Pribadi."}</p>
      <p class="muted">Ruang Kirim <span id="ver"></span> · @SuS_X_x</p>
      <div class="acts">${HP ? '<button class="btn dan" data-a="quit">Matikan (berhenti menerima)</button>' : ""}<button class="btn pri" data-x>Tutup</button></div>`, (e) => {
      if (e.target.closest("[data-x]")) return closeModal();
      const t = e.target.closest("[data-sw]");
      if (t) { const k = t.dataset.sw, v = !S.settings[k]; S.settings[k] = v; t.classList.toggle("on", v); RK.call("setting", k, v); return; }
      const a = e.target.closest("[data-a]")?.dataset.a;
      if (a === "rename") renameDialog();
      if (a === "dir") RK.call("chooseDir").then(() => setTimeout(openSettings, 200));
      if (a === "quit") RK.call("quit");
    });
    RK.call("version").then((v) => { const el = $("#ver"); if (el) el.textContent = v; }).catch(() => {});
  }
  $("#bSet").onclick = openSettings;

  // ---------- mulai ----------
  $("#bQr").hidden = HP;
  $("#bScan").hidden = !HP;
  function render(s) {
    S = s;
    renderMe(); renderPending(); renderDevices(); renderTransfers(); syncPrompts();
  }
  RK.onState(render);
  RK.call("state").then(render).catch(() => {});
  window.RKUI = { back() { if (!modal.hidden && !shownPrompt) { closeModal(); return true; } return false; }, toast };
})();
