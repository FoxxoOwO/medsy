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
        if (activePageId === 'page-today') this.#renderToday();
        if (activePageId === 'page-manager') this.#renderManager();
        if (activePageId === 'page-stats') this.#renderStats();
    }

    #renderToday() {
        const today = new Date().toISOString().split('T')[0];
        document.getElementById('current-date-display').textContent = new Date().toLocaleDateString('cs-CZ');
        this.todayGrid.innerHTML = '';
        
    }

    #renderManager() {
        this.managerList.innerHTML = '';
    }

    #renderStats() {
        let total = 0;
        document.getElementById('stat-total-taken').innerText = total;

        const grid = document.getElementById('calendar-grid');
        grid.innerHTML = '';
        const date = new Date();
        const daysInMonth = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
        
        document.getElementById('calendar-month-name').innerText = date.toLocaleString('cs-CZ', { month: 'long', year: 'numeric' });

    }

    bindNavigation(handler) {
        this.navButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                this.navButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                
                const target = btn.dataset.target;
                this.pages.forEach(p => p.classList.remove('active'));
                document.getElementById(target).classList.add('active');
                
                handler(target);
            });
        });
    }

}