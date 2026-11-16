import './styles/app.css';
import { Application, Controller } from '@hotwired/stimulus';
const application = Application.start();
application.register('appearance', class extends Controller {
  static targets = ['toggle'];
  connect() {
    try { document.documentElement.dataset.theme = localStorage.getItem('echoscript-appearance') === 'light' ? 'light' : 'dark'; } catch { /* Optional UI preference. */ }
    this.updateLabel();
  }
  toggle() {
    const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('echoscript-appearance', theme); } catch { /* Optional UI preference. */ }
    this.updateLabel();
  }
  updateLabel() {
    const label = document.documentElement.dataset.theme === 'dark' ? 'Light appearance' : 'Dark appearance';
    this.toggleTarget.textContent = label;
    this.toggleTarget.setAttribute('aria-label', 'Switch to ' + label.toLowerCase());
  }
});
