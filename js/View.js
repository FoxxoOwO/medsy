class View {
    constructor() {
        this.todayGrid = document.getElementById('today-grid');
        this.managerList = document.getElementById('manager-list-body');
        
        this.dialog = document.getElementById('med-dialog');
        this.form = document.getElementById('med-form');
        this.timesContainer = document.getElementById('times-container');
        
        this.btnAddTime = document.getElementById('btn-add-time');
        this.btnOpenAdd = document.getElementById('btn-open-add');
        this.btnCancel = document.getElementById('btn-cancel');
        this.btnNotif = document.getElementById('btn-enable-notif');

        this.pages = document.querySelectorAll('.page-section');
        this.navButtons = document.querySelectorAll('.nav-item');
    }

    render(meds, activePageId = 'page-today') {
        if (activePageId === 'page-today') this.#renderToday(meds);
        if (activePageId === 'page-manager') this.#renderManager(meds);
        if (activePageId === 'page-stats') this.#renderStats(meds);
    }

    //vyklreslení dnes
    #renderToday(meds) {
        //dnešní datum
        const today = new Date().toISOString().split('T')[0];
        document.getElementById('current-date-display').textContent = new Date().toLocaleDateString('cs-CZ');
        this.todayGrid.innerHTML = '';
        
        //vytvoření pole s dnešními léky a jejich časy
        let schedule = [];
        meds.forEach(med => {
            med.times.forEach(time => {
                schedule.push({ med, time, isTaken: med.isTakenAt(today, time) });
            });
        });
        schedule.sort((a, b) => a.time.localeCompare(b.time));

        //vykreslení karet s léky
        let takenCount = 0;
        schedule.forEach(item => {
            if(item.isTaken) takenCount++;
            const div = document.createElement('div');
            div.className = `med-card ${item.isTaken ? 'taken' : ''}`;
            div.innerHTML = `
                <h3>${item.med.name}</h3>
                <p>${item.med.dosage} • <strong>${item.time}</strong></p>
                <div class="card-actions">
                    <button class="btn-filled action-take" data-id="${item.med.id}" data-time="${item.time}">
                        ${item.isTaken ? 'Splněno' : 'Užít'}
                    </button>
                </div>
            `;
            this.todayGrid.appendChild(div);
        });

        // Progress bar
        const percent = schedule.length ? Math.round((takenCount/schedule.length)*100) : 0;
        document.getElementById('progress-bar').style.width = `${percent}%`;
        document.getElementById('progress-text').innerText = `${percent}%`;

        // Empty state
        document.getElementById('today-empty').className = schedule.length === 0 ? 'empty-state' : 'hidden';
    }

    //vykreslení správy léků
    #renderManager(meds) {
        this.managerList.innerHTML = '';
        meds.forEach(med => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><b>${med.name}</b></td>
                <td>${med.dosage}</td>
                <td>${med.times.join(', ')}</td>
                <td style="text-align: right;">
                    <button class="btn-icon edit" data-id="${med.id}"><span class="material-symbols-outlined">edit</span></button>
                    <button class="btn-icon delete" data-id="${med.id}"><span class="material-symbols-outlined">delete</span></button>
                </td>
            `;
            this.managerList.appendChild(tr);
        });
    }

    //vykreslení statistik
    #renderStats(meds) {
        let total = 0;
        meds.forEach(m => total += m.history.length);
        document.getElementById('stat-total-taken').innerText = total;

        const grid = document.getElementById('calendar-grid');
        grid.innerHTML = '';
        const date = new Date();
        const daysInMonth = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
        
        document.getElementById('calendar-month-name').innerText = date.toLocaleString('cs-CZ', { month: 'long', year: 'numeric' });

        //kalendář
        for(let d=1; d<=daysInMonth; d++) {
            const cell = document.createElement('div');
            cell.className = 'day-cell';
            cell.innerText = d;
            
            const dStr = `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
            let req = 0, done = 0;
            meds.forEach(m => {
                req += m.times.length;
                m.times.forEach(t => { if(m.isTakenAt(dStr, t)) done++; });
            });

            if(dStr === new Date().toISOString().split('T')[0]) cell.classList.add('today');
            
            if(req > 0 && new Date(dStr) <= new Date()) {
                if(done === req) cell.classList.add('status-full');
                else if(done > 0) cell.classList.add('status-partial');
                else cell.classList.add('status-none');
            }
            grid.appendChild(cell);
        }
    }


    bindNavigation(handler) {
        this.navButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                //classy navigace
                this.navButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                
                //classy stránek (nastavuje aktivní stránku)
                const target = btn.dataset.target;
                this.pages.forEach(p => p.classList.remove('active'));
                document.getElementById(target).classList.add('active');
                
                handler(target);
            });
        });
    }

    bindToggleTaken(handler) {
        this.todayGrid.addEventListener('click', e => {
            const btn = e.target.closest('.action-take');
            if(btn) handler(btn.dataset.id, btn.dataset.time);
        });
    }

    bindDeleteOrEdit(deleteHandler, editHandler) {
        this.managerList.addEventListener('click', e => {
            const btn = e.target.closest('button');
            if(!btn) return;
            if(btn.classList.contains('delete')) deleteHandler(btn.dataset.id);
            if(btn.classList.contains('edit')) editHandler(btn.dataset.id);
        });
    }

    bindFormSubmit(handler) {
        this.form.addEventListener('submit', (e) => {
            const inputs = document.querySelectorAll('.med-time-input');
            const times = Array.from(inputs).map(i => i.value).filter(v=>v);
            
            if(times.length === 0) { alert('Zadejte čas'); return; }

            const data = {
                id: document.getElementById('med-id').value,
                name: document.getElementById('med-name').value,
                dosage: document.getElementById('med-dosage').value,
                times: times
            };
            handler(data);
        });
    }

    //přidání listeneru na tlačítko přidání času užití
    bindAddTimer(handler) {
        this.btnAddTime.addEventListener('click', () => {
            this.#addTimeInput();
        });
    }

    //otevření dialogu pro přidání/úpravu léku
    openDialog(med = null) {
        this.form.reset();
        this.timesContainer.innerHTML = '';
        document.getElementById('med-id').value = '';
        
        if(med) { //pokud je předán lék, upravuje se
            document.getElementById('dialog-title').innerText = 'Upravit';
            document.getElementById('med-id').value = med.id;
            document.getElementById('med-name').value = med.name;
            document.getElementById('med-dosage').value = med.dosage;
            med.times.forEach(t => this.#addTimeInput(t));
        } else { //jinak nový
            document.getElementById('dialog-title').innerText = 'Nový lék';
            this.#addTimeInput();
        }
        
        this.dialog.showModal();
    }

    //listenery pro otevírání/zavírání dialogu
    initDialogListeners() {
        this.btnOpenAdd.addEventListener('click', () => this.openDialog());
        this.btnCancel.addEventListener('click', () => this.dialog.close());
    }

    //přidání elementu pro čas užití
    #addTimeInput(val = '') {
        const div = document.createElement('div');
        div.style.cssText = 'display:flex; gap:5px; margin-bottom:5px;';
        div.innerHTML = `
            <input type="time" class="med-time-input" value="${val}" required style="flex:1; padding:10px;">
            <button type="button" class="btn-text" onclick="this.parentElement.remove()">X</button>
        `;
        this.timesContainer.appendChild(div);
    }
}