const params = new URLSearchParams(location.hash.slice(1));
const code = params.get('code') || '';
const query = new URLSearchParams({address: location.origin});
if (/^\d{6}$/.test(code)) {
  query.set('code', code);
  document.querySelector('#pair-hint').textContent = 'Код подключения: ' + code + '. Открой приложение кнопкой выше — адрес и код подставятся сами.';
}
document.querySelector('#open-app').href = 'zapas://pair?' + query;
