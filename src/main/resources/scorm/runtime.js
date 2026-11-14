/* SCORM 1.2 synchronous API; persistence uses a serialized, bounded retry queue on an isolated origin. */
(() => {
  'use strict';
  const boot = window.SCORM_BOOTSTRAP;
  const values = { ...boot.cmi };
  const writable = key => /^cmi\.core\.(lesson_location|lesson_status|exit|session_time|score\.(raw|min|max))$/.test(key)
    || /^cmi\.(suspend_data|comments|student_preference\.(audio|language|speed|text))$/.test(key)
    || /^cmi\.(interactions|objectives)\.[0-9]{1,4}\.[A-Za-z0-9_.]+$/.test(key);
  const statuses = ['passed','completed','failed','incomplete','browsed','not attempted'];
  const errors = {'0':'No error','101':'General exception','201':'Invalid argument error',
    '202':'Element cannot have children','203':'Element not an array','301':'Not initialized',
    '401':'Not implemented error','402':'Invalid set value, element is a keyword',
    '403':'Element is read only','404':'Element is write only','405':'Incorrect data type'};
  let initialized = false, finished = false, lastError = '0', dirty = false, sending = false;
  let sequence = boot.sequence || 0, timer, unsaved = null, saveError = false;
  const notify = (state, progress) => {
    const element = document.getElementById('statusText');
    if (element) element.textContent = state === 'error' ? 'Chưa lưu được tiến độ. Kiểm tra mạng rồi thử lại.'
      : boot.preview ? 'Đang xem thử · dữ liệu tách khỏi học viên' : '';
    const status = document.getElementById('status');
    if (status) status.hidden = state !== 'error' && !boot.preview;
    document.body?.classList.toggle('has-status', state === 'error' || boot.preview);
    const retry = document.getElementById('retry');
    if (retry) retry.hidden = state !== 'error';
    window.parent.postMessage({type:'scorm:state',state,progress}, boot.origin);
  };
  const fail = code => { lastError = code; return 'false'; };
  const ok = () => { lastError = '0'; return 'true'; };
  const payload = () => ({sequence:sequence + 1, values:Object.fromEntries(
    Object.entries(values).filter(([key])=>writable(key))), finished});
  const schedule = () => {
    dirty = true;
    clearTimeout(timer);
    timer = setTimeout(save, 150);
  };
  async function save() {
    if (sending || (!dirty && !unsaved)) return;
    sending = true;
    notify('saving');
    const packet = unsaved || payload();
    unsaved = packet;
    dirty = false;
    for (let attempt=0; attempt<3; attempt++) {
      try {
        const response = await fetch('runtime', {method:'POST', credentials:'omit', keepalive:true,
          headers:{'Content-Type':'application/json'},body:JSON.stringify(packet)});
        const result = await response.json();
        if (!response.ok || result.status !== 'SUCCESS' || result.data.sequence !== packet.sequence)
          throw new Error('SCORM save rejected');
        sequence = packet.sequence;
        unsaved = null;
        saveError = false;
        sending = false;
        notify(dirty ? 'saving' : 'saved', result.data.progress);
        if (dirty) void save();
        return;
      } catch {
        if (attempt < 2) await new Promise(resolve=>setTimeout(resolve, 500 * (attempt + 1)));
      }
    }
    lastError = '101';
    saveError = true;
    sending = false;
    notify('error');
  }
  function validate(key, value) {
    if (value.length > (key === 'cmi.core.lesson_location' ? 255 : 4096)) return false;
    if (key === 'cmi.core.lesson_status') return statuses.includes(value);
    if (key === 'cmi.core.exit') return ['', 'time-out','suspend','logout'].includes(value);
    if (key === 'cmi.core.session_time') return /^[0-9]{2,4}:[0-5][0-9]:[0-5][0-9](\.[0-9]{1,2})?$/.test(value);
    if (/^cmi\.core\.score\./.test(key)) return value === '' || /^-?\d+(\.\d{1,6})?$/.test(value) && Math.abs(Number(value))<=10000;
    return true;
  }
  window.API = {
    LMSInitialize(argument) {
      if (argument !== '') return fail('201');
      if (initialized || finished) return fail('101');
      initialized = true;
      if (values['cmi.core.lesson_status'] === 'not attempted') values['cmi.core.lesson_status'] = 'incomplete';
      schedule();
      return ok();
    },
    LMSFinish(argument) {
      if (argument !== '') return fail('201');
      if (!initialized || finished) return fail('301');
      finished = true;
      initialized = false;
      schedule();
      void save();
      return ok();
    },
    LMSGetValue(key) {
      if (!initialized) { fail('301'); return ''; }
      lastError = '0';
      const children = {
        'cmi.core._children':'student_id,student_name,lesson_location,credit,lesson_status,entry,score,total_time,lesson_mode,exit,session_time',
        'cmi.core.score._children':'raw,min,max',
        'cmi.student_data._children':'mastery_score,max_time_allowed,time_limit_action',
        'cmi.student_preference._children':'audio,language,speed,text',
        'cmi.objectives._children':'id,score,status',
        'cmi.interactions._children':'id,objectives,time,type,correct_responses,weighting,student_response,result,latency'};
      if (key in children) return children[key];
      if (key === 'cmi.objectives._count' || key === 'cmi.interactions._count') {
        const prefix = key.substring(0,key.lastIndexOf('.')) + '.';
        const indices = Object.keys(values).filter(k=>k.startsWith(prefix)).map(k=>Number(k.slice(prefix.length).split('.')[0])).filter(Number.isInteger);
        return String(indices.length ? Math.max(...indices)+1 : 0);
      }
      if (/^cmi\.interactions\.\d+\./.test(key) || key === 'cmi.core.exit' || key === 'cmi.core.session_time') { fail('404'); return ''; }
      if (key in values) return values[key];
      if (writable(key) || ['cmi.launch_data','cmi.comments_from_lms','cmi.student_data.max_time_allowed',
        'cmi.student_data.time_limit_action','cmi.student_data.mastery_score'].includes(key)) return '';
      fail('401'); return '';
    },
    LMSSetValue(key, input) {
      if (!initialized) return fail('301');
      if (typeof key !== 'string' || typeof input !== 'string') return fail('201');
      if (key.endsWith('._count') || key.endsWith('._children')) return fail('402');
      if (!writable(key)) return fail(key in values ? '403' : '401');
      if (!validate(key,input)) return fail('405');
      const next = {...values,[key]:input};
      if (Object.keys(next).filter(writable).length > 600 || new TextEncoder().encode(JSON.stringify(next)).length > 57000) return fail('405');
      values[key] = input;
      schedule();
      return ok();
    },
    LMSCommit(argument) {
      if (argument !== '') return fail('201');
      if (!initialized) return fail('301');
      if (saveError) { void save(); return fail('101'); }
      dirty = true;
      void save();
      return ok();
    },
    LMSGetLastError() { return lastError; },
    LMSGetErrorString(code) { return errors[code] || ''; },
    LMSGetDiagnostic(code) { return errors[code || lastError] || ''; }
  };
  window.addEventListener('DOMContentLoaded', () => {
    document.getElementById('retry').onclick = () => void save();
    document.getElementById('content').src = boot.launch;
    notify('saved');
  });
  window.addEventListener('pagehide', () => {
    clearTimeout(timer);
    if (!dirty && !unsaved) return;
    // A complete newer snapshot wins even if an earlier in-flight write arrives after this flush.
    const packet = dirty ? {...payload(),sequence:(unsaved?.sequence || sequence)+1} : unsaved || payload();
    void fetch('runtime',{method:'POST',credentials:'omit',keepalive:true,
      headers:{'Content-Type':'application/json'},body:JSON.stringify(packet)}).catch(()=>{});
  });
})();
