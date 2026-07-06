import { mount, unmount } from "svelte";
import App from "./App.svelte";

let instance = null;
function mountApp() {
  instance = mount(App, { target: document.getElementById("main") });
}
mountApp();
window.__mount = mountApp;
window.__unmount = () => {
  if (instance) unmount(instance);
  instance = null;
};
