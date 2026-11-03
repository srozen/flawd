import BpmnModeler from 'bpmn-js/lib/Modeler';
import { BpmnPropertiesPanelModule, BpmnPropertiesProviderModule } from 'bpmn-js-properties-panel';

import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css';
import './editor.css';

const NEW_DIAGRAM = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="152" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
`;

const CHANGE_DEBOUNCE_MS = 120;

const $ = id => document.getElementById(id);

/**
 * Messages to the IDE are a single string: `<type>\n<payload>`.
 * Outside the IDE (plain browser) they are logged so the page can be tried standalone.
 */
function send(type, payload = '') {
  if (window.__ide) {
    window.__ide.send(type + '\n' + payload);
  } else {
    console.debug('[bpmn -> ide]', type, payload.length > 200 ? payload.slice(0, 200) + '…' : payload);
  }
}

let modeler = null;
let theme = { dark: false };
/** The XML the diagram and the IDE document last agreed on. */
let lastXml = null;
let importing = false;
let broken = false;
let changeTimer = null;

function rendererColors() {
  return theme.dark
    ? { defaultFillColor: theme.background || '#1e1f22', defaultStrokeColor: '#ced0d6', defaultLabelColor: '#dfe1e5' }
    : { defaultFillColor: '#ffffff', defaultStrokeColor: '#1e1f22', defaultLabelColor: '#1e1f22' };
}

function createModeler() {
  if (modeler) {
    modeler.destroy();
    $('properties').innerHTML = '';
  }

  modeler = new BpmnModeler({
    container: '#canvas',
    propertiesPanel: { parent: '#properties' },
    additionalModules: [BpmnPropertiesPanelModule, BpmnPropertiesProviderModule],
    bpmnRenderer: rendererColors(),
    textRenderer: { defaultStyle: { fontFamily: 'Arial, sans-serif' } }
  });

  // The IDE owns undo/redo: diagram edits land in the document as undoable commands,
  // and undoing them there re-imports the diagram. This keeps text and diagram history unified.
  const editorActions = modeler.get('editorActions');
  for (const action of ['undo', 'redo']) {
    if (editorActions.isRegistered(action)) editorActions.unregister(action);
    editorActions.register(action, () => send(action));
  }

  const eventBus = modeler.get('eventBus');

  eventBus.on('commandStack.changed', () => {
    if (importing || broken) return;
    clearTimeout(changeTimer);
    changeTimer = setTimeout(flushChange, CHANGE_DEBOUNCE_MS);
  });

  eventBus.on('selection.changed', ({ newSelection }) => {
    if (newSelection.length !== 1) return;
    const element = newSelection[0].labelTarget || newSelection[0];
    const id = element.businessObject && element.businessObject.id;
    if (id) send('select', id);
  });
}

async function flushChange() {
  changeTimer = null;
  if (importing || broken) return;
  try {
    const { xml } = await modeler.saveXML({ format: true });
    if (xml !== lastXml) {
      lastXml = xml;
      send('changed', xml);
    }
  } catch (e) {
    send('log', 'saveXML failed: ' + (e && e.message));
  }
}

async function importXml(xml, { keepView = true, force = false } = {}) {
  if (!force && !broken && xml === lastXml) return;

  // A pending diagram edit would otherwise be serialized on top of the incoming text
  clearTimeout(changeTimer);
  changeTimer = null;
  lastXml = xml;

  const canvas = modeler.get('canvas');
  const wasBroken = broken;
  let viewbox = null;
  if (keepView && !wasBroken) {
    try {
      const { x, y, width, height } = canvas.viewbox();
      viewbox = { x, y, width, height };
    } catch (e) { /* nothing rendered yet */ }
  }

  importing = true;
  try {
    const { warnings } = await modeler.importXML(xml.trim() ? xml : NEW_DIAGRAM);
    broken = false;
    $('error').hidden = true;
    showWarnings(warnings);
    if (viewbox && viewbox.width > 0) {
      canvas.viewbox(viewbox);
    } else {
      fit();
    }
  } catch (e) {
    broken = true;
    $('warnings').hidden = true;
    $('error-message').textContent = (e && e.message) || String(e);
    $('error').hidden = false;
  } finally {
    importing = false;
  }
}

function showWarnings(warnings) {
  const el = $('warnings');
  if (!warnings || warnings.length === 0) {
    el.hidden = true;
    return;
  }
  el.textContent = `${warnings.length} import warning${warnings.length > 1 ? 's' : ''}: ${warnings[0].message}` +
    (warnings.length > 1 ? ' (click to dismiss)' : '');
  el.title = warnings.map(w => w.message).join('\n');
  el.onclick = () => { el.hidden = true; };
  el.hidden = false;
}

function applyTheme(next) {
  theme = { ...theme, ...next };
  const root = document.documentElement;
  root.classList.toggle('ide-dark', !!theme.dark);
  if (theme.background) root.style.setProperty('--ide-background', theme.background);
  if (theme.foreground) root.style.setProperty('--ide-foreground', theme.foreground);
  if (theme.border) root.style.setProperty('--ide-border', theme.border);
  if (theme.font) root.style.setProperty('--ide-font', `"${theme.font}", sans-serif`);
}

function fit() {
  const canvas = modeler.get('canvas');
  const root = canvas.getRootElement();
  if (root && root.children && root.children.length) {
    canvas.zoom('fit-viewport', 'auto');
  } else {
    canvas.zoom(1);
  }
}

function setupSplitter() {
  const splitter = $('splitter');
  const panel = $('properties');
  splitter.addEventListener('mousedown', down => {
    down.preventDefault();
    const startX = down.clientX;
    const startWidth = panel.getBoundingClientRect().width;
    const move = e => { panel.style.width = Math.max(200, startWidth + (startX - e.clientX)) + 'px'; };
    const up = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      send('propertiesWidth', String(Math.round(panel.getBoundingClientRect().width)));
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  });

  // diagram-js caches the container size; tell it whenever the canvas area changes
  new ResizeObserver(() => modeler && modeler.get('canvas').resized()).observe($('canvas'));
}

window.addEventListener('error', e => send('log', `${e.message} (${e.filename}:${e.lineno})`));
window.addEventListener('unhandledrejection', e => send('log', 'Unhandled rejection: ' + (e.reason && e.reason.stack || e.reason)));

/** API called by the IDE through executeJavaScript. */
window.bpmnEditor = {
  init({ xml, theme: initialTheme, propertiesVisible = true, propertiesWidth }) {
    applyTheme(initialTheme || {});
    this.setPropertiesVisible(propertiesVisible);
    if (propertiesWidth) $('properties').style.width = propertiesWidth + 'px';
    createModeler();
    return importXml(xml || '', { keepView: false, force: true });
  },

  importXml(xml) {
    return importXml(xml);
  },

  setTheme(next) {
    applyTheme(next);
    // Renderer colors are fixed at construction, so rebuild the modeler and re-render
    createModeler();
    return importXml(lastXml || '', { keepView: false, force: true });
  },

  setPropertiesVisible(visible) {
    $('app').classList.toggle('no-properties', !visible);
  },

  fit,

  async exportSvg() {
    const { svg } = await modeler.saveSVG();
    send('svg', svg);
  }
};

setupSplitter();
