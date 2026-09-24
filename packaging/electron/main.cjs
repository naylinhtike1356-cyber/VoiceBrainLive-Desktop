const { app, dialog } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let child;

function composeAppDir() {
  return path.join(process.resourcesPath, 'VoiceBrainLive');
}

function startComposeApp() {
  const appDir = composeAppDir();
  const executable = path.join(appDir, 'VoiceBrainLive.exe');
  child = spawn(executable, process.argv.slice(1), {
    cwd: appDir,
    detached: false,
    windowsHide: true,
    env: { ...process.env, VOICEBRAIN_WRAPPER: 'electron' },
  });

  child.on('error', (error) => {
    dialog.showErrorBox('VoiceBrainLive could not start', `${error.message}\n\nExpected executable:\n${executable}`);
    app.quit();
  });

  child.on('exit', (code) => {
    app.exit(typeof code === 'number' ? code : 0);
  });
}

app.whenReady().then(() => {
  startComposeApp();
});

app.on('window-all-closed', () => {
  if (child && !child.killed) child.kill();
  app.quit();
});

app.on('before-quit', () => {
  if (child && !child.killed) child.kill();
});
