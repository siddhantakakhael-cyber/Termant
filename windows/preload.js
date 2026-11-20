// The only door between the window's page and the main process.
const { contextBridge, ipcRenderer } = require('electron');

const EVENTS = ['chat', 'chats', 'data', 'install', 'installed', 'ring', 'ask', 'toast', 'shells', 'term:data', 'term:exit'];

contextBridge.exposeInMainWorld('termant', {
  call: async (name, argument) => {
    const result = await ipcRenderer.invoke(name, argument);
    if (!result.ok) throw new Error(result.error);
    return result.value;
  },
  on: (channel, listener) => {
    if (EVENTS.includes(channel)) ipcRenderer.on(channel, (event, value) => listener(value));
  },
});
