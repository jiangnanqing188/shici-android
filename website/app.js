(function () {
  "use strict";

  var HERO_PHRASE = "阅读不停，生词随手拾起";
  var site = window.SHICI_SITE;

  function text(value) {
    if (value === null || value === undefined) {
      return "";
    }
    return String(value).replace(/ /g, " ").trim();
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

  function safeHttpUrl(value) {
    var raw = text(value);
    if (!raw) {
      return "";
    }
    try {
      var url = new URL(raw);
      if (url.protocol !== "http:" && url.protocol !== "https:") {
        return "";
      }
      if (url.username || url.password) {
        return "";
      }
      return url.href;
    } catch (error) {
      return "";
    }
    return "";
  }

  function isPositiveInteger(value) {
    var raw = text(value);
    if (!/^[0-9]+$/.test(raw)) {
      return false;
    }
    var number = Number(raw);
    return Number.isSafeInteger(number) && number > 0;
  }

  function isSha256(value) {
    return /^[0-9a-fA-F]{64}$/.test(text(value));
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
    if (!release || typeof release !== "object") {
      return { state: "preparing", version: "" };
    }
    var version = text(release.version);
    var versionCode = text(release.versionCode);
    var sizeLabel = text(release.sizeLabel);
    var checksum = text(release.sha256);
    var downloadUrl = safeHttpUrl(release.url);
    var notesUrl = safeHttpUrl(release.releaseNotesUrl);
    var isComplete = Boolean(version) &&
      isPositiveInteger(versionCode) &&
      Boolean(sizeLabel) &&
      isSha256(checksum) &&
      text(release.channel) === "test" &&
      Boolean(downloadUrl);

    return {
      state: isComplete ? "open" : "incomplete",
      version: version,
      versionCode: versionCode,
      sizeLabel: sizeLabel,
      checksum: checksum,
      downloadUrl: downloadUrl,
      notesUrl: notesUrl
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

  function renderSiteText(description) {
    var siteName = text(site.name) || "拾词";
    var tagline = text(site.tagline);
    var descriptionText = text(description);
    var taglineNode = document.querySelector("[data-tagline]");
    var descriptionNode = document.querySelector("[data-description]");
    var metaDescription = document.querySelector('meta[name="description"]');

    setAllText("site-name", siteName);
    document.title = siteName + "｜" + HERO_PHRASE;

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

    if (metaDescription && descriptionText) {
      metaDescription.setAttribute("content", descriptionText);
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

  function appendMetaRow(list, label, value, monospace) {
    var clean = text(value);
    if (!clean) {
      return;
    }
    list.appendChild(element("dt", "", label));
    var definition = element("dd");
    if (monospace) {
      definition.appendChild(element("code", "", clean));
    } else {
      definition.textContent = clean;
    }
    list.appendChild(definition);
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
    nodes.channel.textContent = copy.channel;
    nodes.title.textContent = copy.title;
    nodes.copy.textContent = copy.copy;
    nodes.verify.textContent = copy.verify;
    nodes.actions.replaceChildren();
    nodes.actions.hidden = true;
    nodes.meta.replaceChildren();
    nodes.meta.hidden = true;
    nodes.notes.replaceChildren();
    nodes.notes.hidden = true;
  }

  function renderRelease(nodes, info, copy) {
    applyReleaseCopy(nodes, copy);

    if (info.state !== "open") {
      return;
    }

    var downloadLink = element("a", "button", "下载安装包");
    downloadLink.href = info.downloadUrl;
    downloadLink.rel = "noopener";
    downloadLink.setAttribute("aria-describedby", "release-action-note");

    var actionNote = element("p", "action-note", "将前往外部下载地址，不会自动开始下载。");
    actionNote.id = "release-action-note";
    nodes.actions.appendChild(downloadLink);
    nodes.actions.appendChild(actionNote);
    nodes.actions.hidden = false;

    var meta = document.createDocumentFragment();
    appendMetaRow(meta, "版本", info.version);
    appendMetaRow(meta, "版本代码", info.versionCode);
    appendMetaRow(meta, "文件大小", info.sizeLabel);
    appendMetaRow(meta, "SHA-256", info.checksum, true);
    nodes.meta.replaceChildren(meta);
    nodes.meta.hidden = !nodes.meta.childElementCount;

    if (info.notesUrl) {
      var notesText = document.createTextNode("更新说明：");
      var notesLink = element("a", "", "查看本版更新说明");
      notesLink.href = info.notesUrl;
      notesLink.rel = "noopener";
      nodes.notes.replaceChildren(notesText, notesLink);
      nodes.notes.hidden = false;
    }
  }

  function renderDownload(info, copy) {
    var channel = document.querySelector("[data-release-channel]");
    var title = document.querySelector("[data-release-title]");
    var copyNode = document.querySelector("[data-release-copy]");
    var actions = document.querySelector("[data-release-actions]");
    var requirements = document.querySelector("[data-requirements]");
    var verify = document.querySelector("[data-verify-copy]");
    var meta = document.querySelector("[data-release-meta]");
    var notes = document.querySelector("[data-release-notes]");
    var internalVersion = text(site.internalVersion);
    var footerStatus = document.querySelector("[data-footer-status]");
    var heroStatus = document.querySelector("[data-hero-status]");

    if (!channel || !title || !copyNode || !actions || !requirements || !verify || !meta || !notes) {
      return;
    }

    var nodes = {
      channel: channel,
      title: title,
      copy: copyNode,
      actions: actions,
      verify: verify,
      meta: meta,
      notes: notes
    };

    setAllText("internal-version", internalVersion || "未提供");
    setAllText("footer-version", internalVersion || "未提供");
    renderRequirements(requirements, copy.requirement);
    renderRelease(nodes, info, copy);

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
    var repositoryUrl = safeHttpUrl(site.repositoryUrl);
    if (!container) {
      return;
    }
    if (!repositoryUrl) {
      container.replaceChildren();
      container.hidden = true;
      return;
    }

    var link = element("a", "", "查看公开发布仓库");
    link.href = repositoryUrl;
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

  function showLoadNotice() {
    var main = document.getElementById("main");
    if (!main || document.querySelector(".load-notice")) {
      return;
    }
    var notice = element("div", "load-notice", "页面内容暂时无法加载，请稍后刷新重试。下载信息尚未公布，本页不会自动下载任何文件。");
    notice.setAttribute("role", "status");
    main.insertBefore(notice, main.firstChild);
  }

  function init() {
    if (!site || typeof site !== "object") {
      showLoadNotice();
      return;
    }

    var siteName = text(site.name) || "拾词";
    var releaseInfo = resolveReleaseStatus(site.publicRelease);
    var statusCopy = releaseCopy(releaseInfo, siteName);

    renderSiteText(joinSentences(site.description, statusCopy.description));
    renderFeatures();
    renderSteps();
    renderDownload(releaseInfo, statusCopy);
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
