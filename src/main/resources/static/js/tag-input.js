document.addEventListener('DOMContentLoaded', function () {
    const input = document.getElementById('tagInput');
    const chipsContainer = document.getElementById('tagChips');
    const suggestionsBox = document.getElementById('tagSuggestions');
    const hiddenField = document.getElementById('tagNamesHidden');
    const addBtn = document.getElementById('tagAddBtn');
    if (!input || !chipsContainer || !suggestionsBox || !hiddenField) return;

    let tags = hiddenField.value.split(',').map(t => t.trim()).filter(t => t.length > 0);
    let debounceTimer = null;

    function updateHiddenField() {
        hiddenField.value = tags.join(', ');
    }

    function renderChips() {
        chipsContainer.innerHTML = '';
        tags.forEach(function (name) {
            const chip = document.createElement('span');
            chip.className = 'badge rounded-pill bg-success bg-opacity-75 d-inline-flex align-items-center gap-1 py-2 px-3';
            chip.textContent = name;

            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'btn-close btn-close-white';
            removeBtn.style.fontSize = '0.6rem';
            removeBtn.setAttribute('aria-label', 'Remove tag ' + name);
            removeBtn.addEventListener('click', function () {
                tags = tags.filter(t => t.toLowerCase() !== name.toLowerCase());
                renderChips();
                updateHiddenField();
            });

            chip.appendChild(removeBtn);
            chipsContainer.appendChild(chip);
        });
    }

    function hideSuggestions() {
        suggestionsBox.style.display = 'none';
        suggestionsBox.innerHTML = '';
    }

    function addTag(rawName) {
        const name = rawName.trim();
        if (!name) return;
        const alreadyAdded = tags.some(t => t.toLowerCase() === name.toLowerCase());
        if (!alreadyAdded) {
            tags.push(name);
            renderChips();
            updateHiddenField();
        }
        input.value = '';
        hideSuggestions();
    }

    function showSuggestions(names) {
        const filtered = names.filter(name => !tags.some(t => t.toLowerCase() === name.toLowerCase()));
        if (filtered.length === 0) {
            hideSuggestions();
            return;
        }
        suggestionsBox.innerHTML = '';
        filtered.forEach(function (name) {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'list-group-item list-group-item-action';
            item.textContent = name;
            item.addEventListener('mousedown', function (e) {
                e.preventDefault();
                addTag(name);
            });
            suggestionsBox.appendChild(item);
        });
        suggestionsBox.style.display = 'block';
    }

    input.addEventListener('input', function () {
        clearTimeout(debounceTimer);
        const query = input.value.trim();
        debounceTimer = setTimeout(function () {
            fetch('/tags/search?q=' + encodeURIComponent(query))
                .then(res => res.ok ? res.json() : [])
                .then(showSuggestions)
                .catch(() => hideSuggestions());
        }, 200);
    });

    input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            addTag(input.value);
        }
    });

    input.addEventListener('blur', function () {
        setTimeout(hideSuggestions, 150);
    });

    if (addBtn) {
        addBtn.addEventListener('click', function (e) {
            e.preventDefault();
            addTag(input.value);
            input.focus();
        });
    }

    renderChips();
});
