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

// The screens showcase: pick a screen, turn the device, and let it play
// through the screens on its own until someone picks one.
const showcase = document.querySelector("[data-showcase]");
if (showcase) {
  const DWELL_MS = 5000;
  const tabs = [...showcase.querySelectorAll('[role="tab"]')];
  const shots = [...showcase.querySelectorAll(".stage-shot")];
  const orientButtons = [...showcase.querySelectorAll("[data-orient]:is(button)")];
  const lookButtons = [...showcase.querySelectorAll("[data-look]:is(button)")];
  const caption = showcase.querySelector("[data-caption]");
  const stage = showcase.querySelector('[role="tabpanel"]');
  let current = 0;
  let orientation = showcase.dataset.orientation || "portrait";
  // The app's theme in the screenshots: the site's own (Paper when it is light)
  // until a button picks one.
  let pickedLook = null;
  const look = () => pickedLook ?? (document.documentElement.dataset.theme === "light" ? "paper" : "midnight");
  let timer = null;

  const render = () => {
    const id = tabs[current].dataset.screen;
    tabs.forEach((tab, i) => {
      tab.setAttribute("aria-selected", String(i === current));
      tab.tabIndex = i === current ? 0 : -1;
    });
    const shown = look();
    shots.forEach((shot) => {
      shot.classList.toggle(
        "active",
        shot.dataset.screen === id && shot.dataset.orient === orientation && shot.dataset.look === shown,
      );
    });
    orientButtons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.orient === orientation)));
    lookButtons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.look === shown)));
    showcase.dataset.orientation = orientation;
    showcase.dataset.look = shown;
    stage.setAttribute("aria-labelledby", tabs[current].id);
    caption.textContent = tabs[current].querySelector(".show-text span").textContent;
  };

  const stop = () => {
    clearInterval(timer);
    timer = null;
    showcase.classList.remove("playing");
  };
  const restartProgress = () => {
    // Restart the bar under the selected screen by re-adding the class.
    showcase.classList.remove("playing");
    void showcase.offsetWidth;
    showcase.classList.add("playing");
  };
  const play = () => {
    if (calm) return;
    showcase.style.setProperty("--dwell", `${DWELL_MS}ms`);
    restartProgress();
    timer = setInterval(() => {
      current = (current + 1) % tabs.length;
      render();
      restartProgress();
    }, DWELL_MS);
  };

  const select = (i, focus = false) => {
    stop();
    current = (i + tabs.length) % tabs.length;
    render();
    if (focus) tabs[current].focus();
  };

  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => select(i));
    tab.addEventListener("keydown", (e) => {
      const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
      if (step) {
        e.preventDefault();
        select(current + step, true);
      } else if (e.key === "Home" || e.key === "End") {
        e.preventDefault();
        select(e.key === "Home" ? 0 : tabs.length - 1, true);
      }
    });
  });
  orientButtons.forEach((button) => {
    button.addEventListener("click", () => {
      orientation = button.dataset.orient;
      render();
    });
  });
  lookButtons.forEach((button) => {
    button.addEventListener("click", () => {
      pickedLook = button.dataset.look;
      render();
    });
  });
  // Follow the site's theme toggle while no app theme has been picked.
  new MutationObserver(() => {
    if (!pickedLook) render();
  }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

  // Only play while the showcase is on screen.
  new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting && !timer && showcase.dataset.picked !== "1") play();
    else if (!entry.isIntersecting) stop();
  }, { threshold: 0.35 }).observe(showcase);
  showcase.addEventListener("pointerdown", () => { showcase.dataset.picked = "1"; }, { capture: true });
  showcase.addEventListener("keydown", () => { showcase.dataset.picked = "1"; }, { capture: true });

  render();
}

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
