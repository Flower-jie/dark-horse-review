(function () {
    'use strict';

    var API = '/ai/api';
    var state = {
        token: '',
        controller: null,
        streaming: false
    };

    var els = {
        statusBar: document.getElementById('statusBar'),
        chipMode: document.getElementById('chipMode'),
        chipChunks: document.getElementById('chipChunks'),
        chipModel: document.getElementById('chipModel'),
        tokenBtn: document.getElementById('tokenBtn'),
        messages: document.getElementById('messages'),
        emptyHint: document.getElementById('emptyHint'),
        question: document.getElementById('question'),
        questionCounter: document.getElementById('questionCounter'),
        sendBtn: document.getElementById('sendBtn'),
        stopBtn: document.getElementById('stopBtn'),
        retrievalOnly: document.getElementById('retrievalOnly'),
        chatError: document.getElementById('chatError'),
        docList: document.getElementById('docList'),
        docError: document.getElementById('docError'),
        refreshBtn: document.getElementById('refreshBtn'),
        addForm: document.getElementById('addForm'),
        docTitle: document.getElementById('docTitle'),
        docContent: document.getElementById('docContent'),
        docCounter: document.getElementById('docCounter'),
        addBtn: document.getElementById('addBtn'),
        addError: document.getElementById('addError'),
        tokenModal: document.getElementById('tokenModal'),
        tokenForm: document.getElementById('tokenForm'),
        tokenInput: document.getElementById('tokenInput'),
        tokenError: document.getElementById('tokenError'),
        tokenSubmit: document.getElementById('tokenSubmit')
    };

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function showError(node, message) {
        node.textContent = message || '';
        node.hidden = !message;
    }

    function messageOf(error) {
        if (!error) {
            return '请求失败';
        }
        return error.message || '请求失败';
    }

    function openTokenModal(message) {
        els.tokenModal.hidden = false;
        showError(els.tokenError, message || '');
        els.tokenInput.focus();
        els.tokenInput.select();
    }

    function closeTokenModal() {
        els.tokenModal.hidden = true;
        showError(els.tokenError, '');
    }

    function parseJson(text) {
        if (!text) {
            return null;
        }
        try {
            return JSON.parse(text);
        } catch (e) {
            return null;
        }
    }

    async function request(path, options) {
        var opts = options || {};
        var headers = Object.assign({}, opts.headers || {});
        headers['X-AI-Token'] = state.token;
        if (opts.body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        var response = await fetch(API + path, {
            method: opts.method || 'GET',
            headers: headers,
            body: opts.body
        });
        var text = await response.text();
        var data = parseJson(text);
        if (!response.ok) {
            if (response.status === 401) {
                openTokenModal('访问令牌无效，请重新输入');
            }
            var error = new Error((data && data.message) || ('请求失败（HTTP ' + response.status + '）'));
            error.status = response.status;
            error.code = data && data.code;
            throw error;
        }
        return data;
    }

    function applyStatus(status) {
        if (!status) {
            return;
        }
        els.chipMode.textContent = status.retrievalMode === 'local_hash'
            ? '检索方式：本地词项哈希（非语义）'
            : '检索方式：' + status.retrievalMode;
        els.chipChunks.textContent = '片段：' + status.chunkCount;
        els.chipModel.textContent = status.chatEnabled ? '生成模型：已启用' : '生成模型：未配置';
        els.chipModel.className = 'chip ' + (status.chatEnabled ? 'on' : 'off');
    }

    function refreshStatus() {
        return request('/status').then(function (status) {
            applyStatus(status);
        }).catch(function (error) {
            els.chipMode.textContent = '检索方式：不可用';
            els.chipChunks.textContent = '片段：-';
            els.chipModel.textContent = '生成模型：-';
            els.chipModel.className = 'chip off';
            showError(els.chatError, messageOf(error));
        });
    }

    function scrollToBottom() {
        els.messages.scrollTop = els.messages.scrollHeight;
    }

    function hideEmptyHint() {
        if (els.emptyHint && els.emptyHint.parentNode) {
            els.emptyHint.parentNode.removeChild(els.emptyHint);
        }
        els.emptyHint = null;
    }

    function appendUser(text) {
        hideEmptyHint();
        var wrap = el('div', 'msg user');
        wrap.appendChild(el('div', 'msg-role', '我'));
        wrap.appendChild(el('div', 'msg-body', text));
        els.messages.appendChild(wrap);
        scrollToBottom();
    }

    function createAssistant() {
        hideEmptyHint();
        var wrap = el('div', 'msg assistant');
        wrap.appendChild(el('div', 'msg-role', '助手'));
        var body = el('div', 'msg-body', '');
        wrap.appendChild(body);
        var citations = el('div', 'citations');
        wrap.appendChild(citations);
        els.messages.appendChild(wrap);
        scrollToBottom();
        return { wrap: wrap, body: body, citations: citations, events: 0 };
    }

    function appendNotice(bubble, text) {
        var notice = el('p', 'notice', text);
        bubble.wrap.appendChild(notice);
        scrollToBottom();
    }

    function appendError(bubble, text) {
        var node = el('p', 'msg-error', text);
        bubble.wrap.appendChild(node);
        scrollToBottom();
    }

    function renderCitations(bubble, citations) {
        while (bubble.citations.firstChild) {
            bubble.citations.removeChild(bubble.citations.firstChild);
        }
        if (!citations || !citations.length) {
            return;
        }
        bubble.citations.appendChild(el('div', 'citations-title',
            '引用片段（共 ' + citations.length + ' 条，按相关度排序）'));
        for (var i = 0; i < citations.length; i++) {
            var item = citations[i];
            var details = el('details', 'citation');
            var summaryText = '[' + (i + 1) + '] ' + (item.title || '未命名')
                + ' · ' + (item.source || '')
                + ' · 第 ' + ((item.ordinal || 0) + 1) + ' 块'
                + ' · 相似度 ' + Number(item.score || 0).toFixed(4);
            var summary = el('summary', '', summaryText);
            details.appendChild(summary);
            details.appendChild(el('p', 'citation-text', item.text || ''));
            bubble.citations.appendChild(details);
        }
        scrollToBottom();
    }

    function handleEvent(name, payload, bubble) {
        var data = null;
        if (payload) {
            data = parseJson(payload);
        }
        if (name === 'meta') {
            if (data) {
                renderCitations(bubble, data.citations);
            }
        } else if (name === 'notice') {
            appendNotice(bubble, (data && data.message) || '');
        } else if (name === 'delta') {
            if (data && typeof data.text === 'string') {
                bubble.body.textContent += data.text;
                scrollToBottom();
            }
        } else if (name === 'error') {
            appendError(bubble, (data && data.message) || '模型服务暂时不可用');
        }
    }

    function processLine(line, frame, bubble) {
        if (line === '') {
            if (frame.data.length) {
                var name = frame.name || 'message';
                var payload = frame.data.join('\n');
                frame.name = '';
                frame.data = [];
                handleEvent(name, payload, bubble);
            } else {
                frame.name = '';
            }
            return;
        }
        if (line.charAt(0) === ':') {
            return;
        }
        var colon = line.indexOf(':');
        var field = colon === -1 ? line : line.slice(0, colon);
        var value = colon === -1 ? '' : line.slice(colon + 1);
        if (value.charAt(0) === ' ') {
            value = value.slice(1);
        }
        if (field === 'event') {
            frame.name = value;
        } else if (field === 'data') {
            frame.data.push(value);
        }
    }

    function drain(buffer, frame, bubble) {
        var consumed = 0;
        var index = 0;
        while (index < buffer.length) {
            var ch = buffer.charAt(index);
            if (ch !== '\n' && ch !== '\r') {
                index++;
                continue;
            }
            if (ch === '\r' && index === buffer.length - 1) {
                // 可能是被分块截断的 CRLF，等下一块数据再判断
                break;
            }
            var line = buffer.slice(consumed, index);
            var next = (ch === '\r' && buffer.charAt(index + 1) === '\n') ? index + 2 : index + 1;
            processLine(line, frame, bubble);
            consumed = next;
            index = next;
        }
        return buffer.slice(consumed);
    }

    async function consumeStream(body, bubble) {
        var reader = body.getReader();
        var decoder = new TextDecoder('utf-8');
        var frame = { name: '', data: [] };
        var buffer = '';
        while (true) {
            var chunk = await reader.read();
            if (chunk.done) {
                break;
            }
            buffer += decoder.decode(chunk.value, { stream: true });
            buffer = drain(buffer, frame, bubble);
        }
        // 末尾不完整的帧直接丢弃，避免把半截 JSON 当成回答
        if (buffer.charAt(buffer.length - 1) === '\n' || buffer.charAt(buffer.length - 1) === '\r') {
            buffer = drain(buffer, frame, bubble);
        }
    }

    function setStreaming(streaming) {
        state.streaming = streaming;
        els.sendBtn.disabled = streaming;
        els.stopBtn.disabled = !streaming;
        els.retrievalOnly.disabled = streaming;
    }

    async function runChat(question) {
        state.controller = new AbortController();
        setStreaming(true);
        var bubble = createAssistant();
        try {
            var response = await fetch(API + '/chat', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream',
                    'X-AI-Token': state.token
                },
                body: JSON.stringify({ question: question }),
                signal: state.controller.signal
            });
            if (!response.ok) {
                var text = await response.text();
                var data = parseJson(text);
                if (response.status === 401) {
                    openTokenModal('访问令牌无效，请重新输入');
                }
                throw new Error((data && data.message) || ('请求失败（HTTP ' + response.status + '）'));
            }
            if (!response.body) {
                throw new Error('当前浏览器不支持流式读取');
            }
            await consumeStream(response.body, bubble);
        } catch (error) {
            if (error && error.name === 'AbortError') {
                appendNotice(bubble, '已停止生成，以上为已经收到的内容。');
            } else {
                appendError(bubble, messageOf(error));
            }
        } finally {
            state.controller = null;
            setStreaming(false);
        }
    }

    async function runSearch(question) {
        setStreaming(true);
        var bubble = createAssistant();
        try {
            var data = await request('/search', {
                method: 'POST',
                body: JSON.stringify({ query: question, topK: 5 })
            });
            var citations = data && data.citations ? data.citations : [];
            if (!citations.length) {
                appendNotice(bubble, '知识库中没有足够相关的资料，请先导入或调整问题');
            } else {
                bubble.body.textContent = '仅检索原文（未调用生成模型），以下按相关度排序：';
                renderCitations(bubble, citations);
            }
        } catch (error) {
            appendError(bubble, messageOf(error));
        } finally {
            setStreaming(false);
        }
    }

    function send() {
        if (state.streaming) {
            return;
        }
        var question = els.question.value.trim();
        showError(els.chatError, '');
        if (!question) {
            showError(els.chatError, '请先输入问题');
            return;
        }
        if (question.length > 4000) {
            showError(els.chatError, '问题不能超过 4000 字');
            return;
        }
        appendUser(question);
        els.question.value = '';
        updateQuestionCounter();
        if (els.retrievalOnly.checked) {
            runSearch(question);
        } else {
            runChat(question);
        }
    }

    function updateQuestionCounter() {
        els.questionCounter.textContent = els.question.value.length + ' / 4000';
    }

    function updateDocCounter() {
        els.docCounter.textContent = els.docContent.value.length + ' / 20000';
    }

    function renderDocuments(list) {
        els.docList.textContent = '';
        if (!list || !list.length) {
            els.docList.appendChild(el('p', 'muted', '资料库为空，可在下方新增资料。'));
            return;
        }
        list.forEach(function (item) {
            var doc = el('div', 'doc');
            doc.appendChild(el('div', 'doc-title', item.title || item.source));
            doc.appendChild(el('div', 'doc-meta',
                '标识：' + item.source + '　片段：' + item.chunkCount));
            var actions = el('div', 'doc-actions');
            var remove = el('button', '', '删除');
            remove.type = 'button';
            remove.addEventListener('click', function () {
                removeDocument(item);
            });
            actions.appendChild(remove);
            doc.appendChild(actions);
            els.docList.appendChild(doc);
        });
    }

    async function loadDocuments() {
        showError(els.docError, '');
        try {
            var list = await request('/knowledge');
            renderDocuments(list);
        } catch (error) {
            els.docList.textContent = '';
            els.docList.appendChild(el('p', 'muted', '资料库加载失败'));
            showError(els.docError, messageOf(error));
        }
    }

    async function removeDocument(item) {
        var name = item.title || item.source;
        if (!window.confirm('确定删除资料「' + name + '」？删除后相关片段将无法检索。')) {
            return;
        }
        showError(els.docError, '');
        try {
            await request('/knowledge?source=' + encodeURIComponent(item.source), { method: 'DELETE' });
            await loadDocuments();
            await refreshStatus();
        } catch (error) {
            showError(els.docError, messageOf(error));
        }
    }

    async function addDocument(event) {
        event.preventDefault();
        showError(els.addError, '');
        var title = els.docTitle.value.trim();
        var content = els.docContent.value.trim();
        if (!title) {
            showError(els.addError, '请填写标题');
            return;
        }
        if (title.length > 100) {
            showError(els.addError, '标题不能超过 100 字');
            return;
        }
        if (!content) {
            showError(els.addError, '请填写正文');
            return;
        }
        if (content.length > 20000) {
            showError(els.addError, '正文不能超过 20000 字');
            return;
        }
        els.addBtn.disabled = true;
        try {
            await request('/knowledge', {
                method: 'POST',
                body: JSON.stringify({ title: title, content: content })
            });
            els.addForm.reset();
            updateDocCounter();
            await loadDocuments();
            await refreshStatus();
        } catch (error) {
            showError(els.addError, messageOf(error));
        } finally {
            els.addBtn.disabled = false;
        }
    }

    async function connect(event) {
        event.preventDefault();
        var value = els.tokenInput.value.trim();
        if (!value) {
            showError(els.tokenError, '令牌不能为空');
            return;
        }
        els.tokenSubmit.disabled = true;
        showError(els.tokenError, '');
        state.token = value;
        try {
            var status = await request('/status');
            applyStatus(status);
            closeTokenModal();
            await loadDocuments();
        } catch (error) {
            showError(els.tokenError, messageOf(error));
            state.token = '';
        } finally {
            els.tokenSubmit.disabled = false;
        }
    }

    els.tokenForm.addEventListener('submit', connect);
    els.tokenBtn.addEventListener('click', function () {
        openTokenModal('');
    });
    els.sendBtn.addEventListener('click', send);
    els.stopBtn.addEventListener('click', function () {
        if (state.controller) {
            state.controller.abort();
        }
    });
    els.question.addEventListener('input', updateQuestionCounter);
    els.question.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            send();
        }
    });
    els.docContent.addEventListener('input', updateDocCounter);
    els.addForm.addEventListener('submit', addDocument);
    els.refreshBtn.addEventListener('click', function () {
        loadDocuments();
        refreshStatus();
    });
    els.retrievalOnly.addEventListener('change', function () {
        showError(els.chatError, '');
    });

    updateQuestionCounter();
    updateDocCounter();
    openTokenModal('');
})();
