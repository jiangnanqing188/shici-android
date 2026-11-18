(function () {
  "use strict";

  var HERO_PHRASE = "阅读不停，生词随手拾起";
  var RELEASE_REPOSITORY_URL = "https://github.com/jiangnanqing188/shici-android";
  var site = window.SHICI_SITE;
  var pageName = document.documentElement.getAttribute("data-page") || "";
  var LEGACY_HASH_TARGETS = {
    "#features": "features.html",
    "#how": "guide.html#how",
    "#guide": "guide.html",
    "#download": "download.html",
    "#privacy": "guide.html#privacy",
    "#faq": "guide.html#faq"
  };

  function text(value) {
    if (value === null || value === undefined) {
      return "";
    }
    return String(value).replace(/\u00a0/g, " ").trim();
  }

  function asArray(value) {
    if (Array.isArray(value)) {
      return value;
    }
    if (typeof value === "string" && value.trim()) {
      return [value];
    }
    return [];
  }

  function paragraphList(value) {
    if (Array.isArray(value)) {
      return value.map(text).filter(Boolean);
    }
    var raw = text(value);
    if (!raw) {
      return [];
    }
    return raw.split(/\n\s*\n/).map(function (part) {
      return part.replace(/\s*\n\s*/g, " ").trim();
    }).filter(Boolean);
  }

  function element(tagName, className, content) {
    var node = document.createElement(tagName);
    if (className) {
      node.className = className;
    }
    if (content !== undefined && content !== null) {
      node.textContent = content;
    }
    return node;
  }

  function setAllText(name, value) {
    document.querySelectorAll('[data-' + name + ']').forEach(function (node) {
      node.textContent = value;
    });
  }

  function normalisePhrase(value) {
    return text(value).replace(/[\s，。！？、,.!?：:；;“”"'‘’]/g, "");
  }

  function joinSentences(first, second) {
    return [text(first), text(second)].filter(Boolean).join(" ");
  }

  function substitute(value, variables) {
    var raw = text(value);
    if (!raw) {
      return "";
    }
    return raw.replace(/\{(\w+)\}/g, function (match, key) {
      return Object.prototype.hasOwnProperty.call(variables, key) ? text(variables[key]) : match;
    });
  }

  function resolveReleaseStatus(release) {
    if (release === null) {
      return { state: "preparing" };
    }
    if (!release || typeof release !== "object") {
      return { state: "incomplete" };
    }
    var version = release.version;
    var versionCode = release.versionCode;
    if (typeof version !== "string" || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(version) ||
        !Number.isSafeInteger(versionCode) || versionCode <= 0 || release.channel !== "test") {
      return { state: "incomplete" };
    }
    // 精确匹配规范的设备 Release 页面，不接受跳转参数、替代主机、凭据或附件直链。
    var releasePrefix = RELEASE_REPOSITORY_URL + "/releases/tag/v" + version;
    if (release.phoneReleaseUrl !== releasePrefix + "-phone-test" ||
        release.tabletReleaseUrl !== releasePrefix + "-tablet-test") {
      return { state: "incomplete" };
    }
    return {
      state: "open",
      version: version,
      versionCode: versionCode,
      phoneReleaseUrl: release.phoneReleaseUrl,
      tabletReleaseUrl: release.tabletReleaseUrl
    };
  }

  function releaseCopy(info, siteName) {
    var table = site.releaseStatus && typeof site.releaseStatus === "object" ? site.releaseStatus : {};
    var raw = table[info.state] && typeof table[info.state] === "object" ? table[info.state] : {};
    var variables = { name: siteName, version: info.version || "" };

    return {
      description: substitute(raw.description, variables),
      requirement: substitute(raw.requirement, variables),
      footerNote: substitute(raw.footerNote, variables),
      hero: substitute(raw.hero, variables),
      footerStatus: substitute(raw.footerStatus, variables),
      channel: substitute(raw.channel, variables),
      title: substitute(raw.title, variables),
      copy: substitute(raw.copy, variables),
      verify: substitute(raw.verify, variables)
    };
  }

  function pageMeta() {
    var table = site.pages && typeof site.pages === "object" ? site.pages : {};
    var entry = pageName && table[pageName] && typeof table[pageName] === "object" ? table[pageName] : {};
    return {
      title: text(entry.title),
      description: text(entry.description)
    };
  }

  // 只改本页的标题与 meta 描述；页面名缺失时回落到站点名，不影响其它渲染。
  // meta 描述仍由同一发布状态追加状态句，避免各页出现互相矛盾的事实。
  function renderSiteText(statusDescription) {
    var siteName = text(site.name) || "拾词";
    var tagline = text(site.tagline);
    var meta = pageMeta();
    var descriptionText = meta.description || text(site.description);
    var metaText = joinSentences(descriptionText, statusDescription);
    var taglineNode = document.querySelector("[data-tagline]");
    var descriptionNode = document.querySelector("[data-description]");
    var metaDescription = document.querySelector('meta[name="description"]');

    setAllText("site-name", siteName);
    document.title = meta.title || (siteName + "｜" + HERO_PHRASE);

    if (taglineNode) {
      if (!tagline || normalisePhrase(tagline) === normalisePhrase(HERO_PHRASE)) {
        taglineNode.hidden = true;
      } else {
        taglineNode.textContent = tagline;
      }
    }

    if (descriptionNode) {
      if (!descriptionText || descriptionText === tagline) {
        descriptionNode.hidden = true;
      } else {
        descriptionNode.textContent = descriptionText;
      }
    }

    if (metaDescription && metaText) {
      metaDescription.setAttribute("content", metaText);
    }
  }

  function renderFeatures() {
    var container = document.querySelector("[data-features]");
    if (!container) {
      return;
    }
    var features = asArray(site.features);
    var fragment = document.createDocumentFragment();
    var count = 0;

    features.forEach(function (feature) {
      if (!feature || typeof feature !== "object") {
        return;
      }
      var title = text(feature.title);
      var description = text(feature.description);
      if (!title && !description) {
        return;
      }

      count += 1;
      var article = element("article", "feature-item");
      var index = element("span", "feature-index", String(count).padStart(2, "0"));
      var body = element("div", "feature-body");
      index.setAttribute("aria-hidden", "true");

      if (title) {
        body.appendChild(element("h3", "", title));
      }
      if (description) {
        body.appendChild(element("p", "", description));
      }
      article.appendChild(index);
      article.appendChild(body);
      fragment.appendChild(article);
    });

    if (!count) {
      fragment.appendChild(element("p", "feature-empty", "功能内容暂未提供。"));
    }
    container.replaceChildren(fragment);
  }

  function renderSteps() {
    var container = document.querySelector("[data-steps]");
    if (!container) {
      return;
    }
    var steps = asArray(site.steps);
    var fragment = document.createDocumentFragment();
    var count = 0;

    steps.forEach(function (step) {
      if (!step || typeof step !== "object") {
        return;
      }
      var title = text(step.title);
      var description = text(step.description);
      if (!title && !description) {
        return;
      }

      count += 1;
      var item = element("li", "step");
      var number = element("span", "step-number", String(count).padStart(2, "0"));
      var body = element("div", "step-body");
      number.setAttribute("aria-hidden", "true");

      if (title) {
        body.appendChild(element("h3", "", title));
      }
      if (description) {
        body.appendChild(element("p", "", description));
      }
      item.appendChild(number);
      item.appendChild(body);
      fragment.appendChild(item);
    });

    if (!count) {
      fragment.appendChild(element("li", "steps-empty", "使用步骤暂未提供。"));
    }
    container.replaceChildren(fragment);
    container.setAttribute("role", "list");
  }

  function appendMetaRow(list, label, value) {
    list.appendChild(element("dt", "", label));
    list.appendChild(element("dd", "", value));
  }

  function renderRequirements(container, statusRequirement) {
    var requirements = asArray(site.requirements).map(text).filter(Boolean);
    var statusText = text(statusRequirement);
    if (statusText) {
      requirements.push(statusText);
    }
    var fragment = document.createDocumentFragment();

    requirements.forEach(function (requirement) {
      fragment.appendChild(element("li", "", requirement));
    });
    if (!requirements.length) {
      fragment.appendChild(element("li", "", "运行要求暂未提供。"));
    }
    container.replaceChildren(fragment);
  }

  function applyReleaseCopy(nodes, copy) {
    if (nodes.channel) {
      nodes.channel.textContent = copy.channel;
    }
    if (nodes.title) {
      nodes.title.textContent = copy.title;
    }
    if (nodes.copy) {
      nodes.copy.textContent = copy.copy;
    }
    if (nodes.verify) {
      nodes.verify.textContent = copy.verify;
    }
    if (nodes.meta) {
      nodes.meta.replaceChildren();
      nodes.meta.hidden = true;
    }
  }

  // 只导航到已校验的 Release 页面，不在官网触发附件下载。
  function renderPlatformSlot(slot, label, releaseUrl) {
    if (!slot) {
      return;
    }
    slot.replaceChildren();
    if (releaseUrl) {
      var releaseLink = element("a", "button button-primary", label);
      releaseLink.href = releaseUrl;
      releaseLink.rel = "noopener";
      releaseLink.setAttribute("aria-describedby", "android-release-note");
      slot.appendChild(releaseLink);
    } else {
      slot.appendChild(element("p", "platform-unavailable", "暂未开放下载"));
    }
    slot.hidden = false;
  }

  function renderRelease(nodes, info, copy) {
    applyReleaseCopy(nodes, copy);
    renderPlatformSlot(nodes.phoneSlot, nodes.phoneLabel, info.phoneReleaseUrl);
    renderPlatformSlot(nodes.tabletSlot, nodes.tabletLabel, info.tabletReleaseUrl);
    if (nodes.releaseNote) {
      nodes.releaseNote.textContent = nodes.releaseNoteText;
    }

    if (info.state !== "open") {
      return;
    }

    if (nodes.meta) {
      var meta = document.createDocumentFragment();
      appendMetaRow(meta, "版本", info.version);
      appendMetaRow(meta, "版本代码", info.versionCode);
      nodes.meta.replaceChildren(meta);
      nodes.meta.hidden = false;
    }
  }

  // 苹果区块只更新文字：这里永远不生成 <a>、download 或商店链接。
  function renderApple(platforms) {
    var apple = platforms.apple && typeof platforms.apple === "object" ? platforms.apple : {};
    var phone = text(apple.phoneStatus);
    var tablet = text(apple.tabletStatus);
    var note = text(apple.note);
    if (phone) {
      setAllText("apple-phone", phone);
    }
    if (tablet) {
      setAllText("apple-tablet", tablet);
    }
    if (note) {
      setAllText("apple-note", note);
    }
  }

  // 只渲染本页实际存在的节点；缺少下载区任一局部节点时其它模块照常渲染。
  function renderDownload(info, copy) {
    var platforms = site.platforms && typeof site.platforms === "object" ? site.platforms : {};
    var android = platforms.android && typeof platforms.android === "object" ? platforms.android : {};
    var internalVersion = text(site.internalVersion);
    var internalCode = text(site.internalVersionCode);
    var footerStatus = document.querySelector("[data-footer-status]");
    var heroStatus = document.querySelector("[data-hero-status]");
    var requirements = document.querySelector("[data-requirements]");

    setAllText("internal-version", internalVersion || "未提供");
    setAllText("internal-version-code", internalCode || "未提供");
    setAllText("footer-version", internalVersion || "未提供");

    if (requirements) {
      renderRequirements(requirements, copy.requirement);
    }

    renderRelease({
      channel: document.querySelector("[data-release-channel]"),
      title: document.querySelector("[data-release-title]"),
      copy: document.querySelector("[data-release-copy]"),
      phoneSlot: document.querySelector("[data-android-phone]"),
      tabletSlot: document.querySelector("[data-android-tablet]"),
      phoneLabel: text(android.phoneLabel) || "打开 GitHub 手机版发布页",
      tabletLabel: text(android.tabletLabel) || "打开 GitHub 平板版发布页",
      releaseNote: document.querySelector("[data-android-release-note]"),
      releaseNoteText: text(info.state === "open" ? android.releaseNote : android.releaseNoteUnavailable),
      verify: document.querySelector("[data-verify-copy]"),
      meta: document.querySelector("[data-release-meta]")
    }, info, copy);

    renderApple(platforms);

    if (footerStatus) {
      footerStatus.textContent = copy.footerStatus;
    }
    if (heroStatus) {
      heroStatus.textContent = copy.hero;
    }
  }

  function renderPrivacy() {
    var privacy = site.privacy && typeof site.privacy === "object" ? site.privacy : {};
    var summary = document.querySelector("[data-privacy-summary]");
    var details = document.querySelector("[data-privacy-details]");
    var summaryText = text(privacy.summary);
    var detailParagraphs = paragraphList(privacy.details);

    if (summary) {
      summary.textContent = summaryText;
      summary.hidden = !summaryText;
    }

    if (details) {
      var fragment = document.createDocumentFragment();
      detailParagraphs.forEach(function (paragraph) {
        fragment.appendChild(element("p", "", paragraph));
      });
      details.replaceChildren(fragment);
    }
  }

  function renderFaq() {
    var container = document.querySelector("[data-faq]");
    if (!container) {
      return;
    }
    var faq = asArray(site.faq);
    var fragment = document.createDocumentFragment();
    var count = 0;

    faq.forEach(function (entry) {
      if (!entry || typeof entry !== "object") {
        return;
      }
      var question = text(entry.question);
      var answer = text(entry.answer);
      if (!question) {
        return;
      }

      count += 1;
      var item = element("details", "faq-item");
      var summary = element("summary");
      var sign = element("span", "faq-sign");
      var answerNode = element("div", "faq-answer");
      sign.setAttribute("aria-hidden", "true");
      summary.appendChild(element("span", "faq-question", question));
      summary.appendChild(sign);

      paragraphList(answer).forEach(function (paragraph) {
        answerNode.appendChild(element("p", "", paragraph));
      });

      item.appendChild(summary);
      item.appendChild(answerNode);
      fragment.appendChild(item);
    });

    if (!count) {
      fragment.appendChild(element("p", "faq-empty", "常见问题暂未提供。"));
    }
    container.replaceChildren(fragment);
  }

  function renderRepository() {
    var container = document.querySelector("[data-footer-repository]");
    if (!container) {
      return;
    }
    if (site.repositoryUrl !== RELEASE_REPOSITORY_URL) {
      container.replaceChildren();
      container.hidden = true;
      return;
    }

    var link = element("a", "", "查看公开发布仓库");
    link.href = RELEASE_REPOSITORY_URL;
    link.rel = "noopener";
    container.replaceChildren(document.createTextNode("公开发布仓库："), link);
    container.hidden = false;
  }

  function renderFooter(copy) {
    var footerNote = text(copy.footerNote);
    var note = document.querySelector("[data-footer-note]");
    var year = document.querySelector("[data-year]");

    if (note) {
      note.textContent = footerNote;
      note.hidden = !footerNote;
    }
    if (year) {
      year.textContent = String(new Date().getFullYear());
    }
    renderRepository();
  }

  // 导航当前项：静态 HTML 已写好 aria-current，这里按解析后的实际路径校正一次。
  // 若当前环境无法把任何链接解析成本页（例如本地 file:// 预览），保留 HTML 原值，
  // 不把全部 aria-current 清空。
  function markCurrentNav() {
    function normalise(path) {
      var value = String(path || "").replace(/\/+$/, "");
      if (!value || value === "/index.html") {
        return "/";
      }
      return value;
    }
    var currentPath = normalise(window.location.pathname);
    var links = Array.prototype.slice.call(document.querySelectorAll("[data-nav] a[href]"));
    var matched = links.some(function (link) {
      return normalise(link.pathname) === currentPath;
    });
    if (!matched) {
      return;
    }
    links.forEach(function (link) {
      if (normalise(link.pathname) === currentPath) {
        link.setAttribute("aria-current", "page");
      } else {
        link.removeAttribute("aria-current");
      }
    });
  }

  // 旧单页入口 /?v=...#features 等只在首页/缺省路径转一次到对应独立页面。
  // 指南页自身的 #privacy/#faq 或功能页自身锚点不参与改写，避免同 URL 循环。
  function redirectLegacyHash() {
    var hash = window.location.hash;
    if (!hash || !Object.prototype.hasOwnProperty.call(LEGACY_HASH_TARGETS, hash)) {
      return false;
    }
    var path = window.location.pathname;
    if (path !== "/" && path !== "" && path !== "/index.html") {
      return false;
    }
    window.location.replace(LEGACY_HASH_TARGETS[hash]);
    return true;
  }

  function showLoadNotice() {
    var main = document.getElementById("main");
    if (!main || document.querySelector(".load-notice")) {
      return;
    }
    var notice = element("div", "load-notice", "页面内容暂时无法加载，请稍后刷新重试。本站暂不显示发布入口，也不会自动下载文件。");
    notice.setAttribute("role", "status");
    main.insertBefore(notice, main.firstChild);
  }

  function init() {
    markCurrentNav();
    if (redirectLegacyHash()) {
      return;
    }
    if (!site || typeof site !== "object") {
      showLoadNotice();
      return;
    }

    var siteName = text(site.name) || "拾词";
    var releaseInfo = resolveReleaseStatus(site.publicRelease);
    var statusCopy = releaseCopy(releaseInfo, siteName);

    renderSiteText(statusCopy.description);
    renderDownload(releaseInfo, statusCopy);
    renderFeatures();
    renderSteps();
    renderPrivacy();
    renderFaq();
    renderFooter(statusCopy);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
