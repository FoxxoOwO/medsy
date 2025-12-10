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
        this.#view.initDialogListeners(); 

        this.#model.bindDataChanged(this.onMedListChanged);


    }

    onMedListChanged = (meds) => {
        this.#view.render(meds, this.#activePage);
    }

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

}