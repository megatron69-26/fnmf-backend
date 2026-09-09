document.addEventListener('DOMContentLoaded', function () {
  var forms = document.querySelectorAll('form');
  forms.forEach(function (form) {
    form.addEventListener('submit', function () {
      var btn = form.querySelector('button');
      if (btn) {
        btn.disabled = true;
        btn.innerText = 'Đang xử lý...';
      }
    });
  });
});
