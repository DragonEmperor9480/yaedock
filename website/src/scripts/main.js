// Small touches: reveal on scroll, a live-looking FPS counter, copy buttons,
// and a border under the nav once the page scrolls. The page works without it.
document.documentElement.classList.add("js");

const calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// Reveal sections as they come into view.
const reveal = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (entry.isIntersecting) {
      entry.target.classList.add("in");
      reveal.unobserve(entry.target);
    }
  }
}, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });
document.querySelectorAll(".reveal").forEach((el) => reveal.observe(el));

// The nav gets a hairline once the page has scrolled.
const nav = document.querySelector(".nav");
const onScroll = () => nav.classList.toggle("scrolled", window.scrollY > 8);
window.addEventListener("scroll", onScroll, { passive: true });
onScroll();

// The FPS card wanders around 144 like a real game.
const fps = document.querySelector("[data-fps]");
if (fps && !calm) {
  let value = 144;
  setInterval(() => {
    value = Math.round(Math.min(165, Math.max(118, value + (Math.random() - 0.5) * 14)));
    fps.textContent = value;
  }, 900);
}

// The stat rings drift like live values: each number and its arc move together.
const rings = [...document.querySelectorAll("[data-ring]")].map((ring) => ({
  ring,
  value: Number(ring.style.getPropertyValue("--v")),
  base: Number(ring.style.getPropertyValue("--v")),
  number: ring.querySelector("[data-value]"),
  detail: ring.querySelector("[data-detail]"),
}));
if (rings.length && !calm) {
  setInterval(() => {
    for (const r of rings) {
      // Wander, but stay near where it started, like a real load.
      r.value = Math.round(Math.min(r.base + 22, Math.max(r.base - 22, r.value + (Math.random() - 0.5) * 16)));
      r.ring.style.setProperty("--v", r.value);
      r.number.textContent = r.value;
      const kind = r.ring.dataset.ring;
      if (kind === "memory") r.detail.textContent = `${(16 * r.value / 100).toFixed(1)} of 16 GiB`;
      else r.detail.textContent = `${Math.round((kind === "gpu" ? 40 : 38) + r.value * 0.38)} °C`;
    }
  }, 1600);
}

// Copy buttons copy the commands without their prompts.
document.querySelectorAll("[data-copy]").forEach((block) => {
  const button = block.querySelector(".copy");
  button.addEventListener("click", async () => {
    // Example output is shown for reading, not copied.
    const code = block.querySelector("code").cloneNode(true);
    code.querySelectorAll(".c-out").forEach((out) => out.remove());
    const text = code.textContent
      .split("\n")
      .map((line) => line.replace(/^\$\s?/, ""))
      .join("\n")
      .trim();
    try {
      await navigator.clipboard.writeText(text);
      button.textContent = "Copied";
      button.classList.add("done");
    } catch {
      button.textContent = "Select and copy";
    }
    setTimeout(() => {
      button.textContent = "Copy";
      button.classList.remove("done");
    }, 1800);
  });
});


// Theme: the button saves a choice; until then the page follows the system.
{
  const root = document.documentElement;
  const toggle = document.querySelector("[data-theme-toggle]");
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const system = matchMedia("(prefers-color-scheme: light)");
  const apply = (theme) => {
    root.dataset.theme = theme;
    themeColor.content = theme === "light" ? "#F5F0E8" : "#090B0F";
    toggle?.setAttribute("aria-label", theme === "light" ? "Switch to dark theme" : "Switch to light theme");
  };
  const saved = () => {
    try { return localStorage.getItem("theme"); } catch { return null; }
  };
  apply(root.dataset.theme === "light" ? "light" : "dark");
  toggle?.addEventListener("click", () => {
    const next = root.dataset.theme === "light" ? "dark" : "light";
    try { localStorage.setItem("theme", next); } catch {}
    apply(next);
  });
  system.addEventListener("change", (e) => {
    if (!saved()) apply(e.matches ? "light" : "dark");
  });
}

// Footprint chart: a crosshair that snaps to the nearest sample, on hover and with the arrow keys.
document.querySelectorAll("[data-footprint]").forEach((card) => {
  const { points, phases, end, bucket } = JSON.parse(card.querySelector("[data-points]").textContent);
  const plots = [...card.querySelectorAll(".fp-plot")];
  const maxes = plots.map((plot) => Number(plot.dataset.max));
  const cross = card.querySelector(".fp-cross");
  const tip = card.querySelector(".fp-tip");
  const dots = plots.map((plot) => plot.querySelector(".fp-dot"));
  const field = (name) => tip.querySelector(`[data-tip="${name}"]`);
  const clock = (t) => `${Math.floor(t / 60)}:${String(Math.round(t % 60)).padStart(2, "0")}`;
  let index = -1;

  card.tabIndex = 0;
  card.setAttribute("aria-label", "Memory and CPU over the test. Use the arrow keys to read each moment.");

  const show = (i) => {
    index = Math.max(0, Math.min(points.length - 1, i));
    const [t, mem, cpu] = points[index];
    const phase = phases.find((p) => t >= p.start && t < p.end) || phases[phases.length - 1];
    const box = card.getBoundingClientRect();
    const first = plots[0].getBoundingClientRect();
    const last = plots[plots.length - 1].getBoundingClientRect();
    const left = first.left - box.left + ((t + bucket / 2) / end) * first.width;

    cross.style.left = `${left}px`;
    cross.style.top = `${first.top - box.top}px`;
    cross.style.height = `${last.bottom - first.top}px`;
    [mem, cpu].forEach((value, n) => {
      dots[n].style.left = `${((t + bucket / 2) / end) * 100}%`;
      dots[n].style.top = `${100 - (value / maxes[n]) * 100}%`;
      dots[n].hidden = false;
    });
    field("mem").textContent = `${mem.toFixed(1)} MiB`;
    field("cpu").textContent = `${cpu.toFixed(2)}%`;
    field("phase").textContent = `${phase.label}, at ${clock(t)}`;
    cross.hidden = tip.hidden = false;
    const width = tip.offsetWidth;
    tip.style.left = `${left + 16 + width > box.width ? left - 16 - width : left + 16}px`;
    tip.style.top = `${first.top - box.top}px`;
  };
  const hide = () => {
    cross.hidden = tip.hidden = true;
    dots.forEach((d) => (d.hidden = true));
  };

  card.addEventListener("pointermove", (e) => {
    const r = plots[0].getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right) return hide();
    show(Math.floor((((e.clientX - r.left) / r.width) * end) / bucket));
  });
  card.addEventListener("pointerleave", hide);
  card.addEventListener("blur", hide);
  card.addEventListener("keydown", (e) => {
    const step = { ArrowRight: 1, ArrowLeft: -1, PageUp: 12, PageDown: -12 }[e.key];
    if (e.key === "Home") return e.preventDefault(), show(0);
    if (e.key === "End") return e.preventDefault(), show(points.length - 1);
    if (e.key === "Escape") return hide();
    if (step === undefined) return;
    e.preventDefault();
    show(index < 0 ? 0 : index + step);
  });
});
