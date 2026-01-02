class Controller {
    #model;
    #view;
    #activePage = 'page-today';

    constructor(model, view) {
        this.#model = model;
        this.#view = view;

        this.onMedListChanged(this.#model.getMedications());

        this.#view.bindNavigation(this.handleNavigation);
        this.#view.bindToggleTaken(this.handleToggleTaken);
        this.#view.bindDeleteOrEdit(this.handleDelete, this.handleEdit);
        this.#view.bindFormSubmit(this.handleSubmit);
        this.#view.bindAddTimer();
        this.#view.bindNotificationReq(this.handleNotifReq);
        this.#view.initDialogListeners(); 

        this.#model.bindDataChanged(this.onMedListChanged);

        this.#checkNotifPermission();
        setInterval(() => this.#checkTimeLoop(), 30000);


    }

    // aktualizace zobrazení při změně dat v modelu
    onMedListChanged = (meds) => {
        this.#view.render(meds, this.#activePage);
    }

    // navigace mezi stránkami
    handleNavigation = (pageId) => {
        this.#activePage = pageId;
        this.#view.render(this.#model.getMedications(), pageId);
    }

    handleToggleTaken = (id, time) => {
        const today = new Date().toISOString().split('T')[0];
        this.#model.toggleStatus(id, time, today);
    }

    handleDelete = (id) => {
        if(confirm('Smazat?')) {
            this.#model.remove(id);
        }
    }

    handleEdit = (id) => {
        const med = this.#model.getById(id);
        this.#view.openDialog(med);
    }

    handleSubmit = (data) => {
        this.#model.addOrUpdate(data.name, data.dosage, data.times, data.id);
    }

    handleNotifReq = () => {
        // požádání o povolení notifikací
        Notification.requestPermission().then(p => {
            if(p === 'granted') {
                // potvrzovací notifikace
                new Notification('Medsy', {body: 'Zapnuto!'});
                // aktualizace zobrazení tlačítka
                this.#checkNotifPermission();
            }
        });
    }

    #checkNotifPermission() {
        // kontrola podpory notifikací
        if(!("Notification" in window)) return;
        // zobrazit tlacitko pro povoleni notifikaci pokud jeste neni povoleno ani zakazano
        this.#view.showNotifBtn(Notification.permission === 'default');
    }

    // Kontrola času pro notifikace
    #checkTimeLoop() {
        if(Notification.permission !== 'granted') return;

        // Získání sktuálního data a času - HH:MM a YYYY-MM-DD
        const now = new Date();
        const timeStr = `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`;
        const today = now.toISOString().split('T')[0];

        console.log(`Kontrola notifikací v ${timeStr} ${today}`);

        // Kontrola času a užití pro každý lék
        this.#model.getMedications().forEach(med => {
            if(med.times.includes(timeStr) && !med.isTakenAt(today, timeStr)) {
                new Notification(`Čas na léky: ${med.name}`, { body: `Dávka: ${med.dosage}` });
            }
        });
    }

}