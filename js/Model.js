class Model {
    #meds = [];
    
    onDataChanged = null;

    constructor() {
        this.#meds = this.#loadFromStorage();
    }

    bindDataChanged(handler) {
        this.onDataChanged = handler;
    }

    getMedications() {
        return this.#meds;
    }

    // získání konkrétního léku podle ID
    getById(id) {
        return this.#meds.find(m => m.id === id);
    }

    // přidání nebo aktualizace léku (pokud je předáno ID, jedná se o aktualizaci)
    addOrUpdate(name, dosage, times, id) {
        if (id) {
            const index = this.#meds.findIndex(m => m.id === id);
            // ověření, že lék s daným ID skutečně existuje
            if(index !== -1) {
                const oldMed = this.#meds[index];
                // náhrada starého léku novým, přičemž se zachová historie užití
                this.#meds[index] = new Medication(name, dosage, times, id, oldMed.history);
            }
        } else {
            const newMed = new Medication(name, dosage, times);
            this.#meds.push(newMed);
        }
        
        this.#commit();
    }

    remove(id) {
        this.#meds = this.#meds.filter(m => m.id !== id);
        this.#commit();
    }

    toggleStatus(id, time, dateStr) {
        const med = this.getById(id);
        if(med) {
            med.toggleTaken(dateStr, time);
            this.#commit();
        }
    }

    // uložení změn a oznámení o změně dat
    #commit() {
        this.#saveToStorage(this.#meds);
        if(this.onDataChanged) {
            this.onDataChanged(this.#meds);
        }
    }


    #saveToStorage(meds) {
        localStorage.setItem('medsy_db', JSON.stringify(meds));
    }

    #loadFromStorage() {
        const data = JSON.parse(localStorage.getItem('medsy_db') || '[]');
        return data.map(d => new Medication(d.name, d.dosage, d.times, d.id, d.history));
    }
}


class Medication {
    constructor(name, dosage, times, id = null, history = []) {
        this.id = id || crypto.randomUUID();
        this.name = name;
        this.dosage = dosage;
        this.times = times; 
        this.history = history;
    }

    // Kontrola jestli byl lék už užit v daný den a čas
    isTakenAt(dateStr, timeStr) {
        return this.history.includes(`${dateStr}|${timeStr}`);
    }

    // zapsaní nebo odebrání záznamu o užití léku
    toggleTaken(dateStr, timeStr) {
        const record = `${dateStr}|${timeStr}`;
        if (this.history.includes(record)) {
            this.history = this.history.filter(h => h !== record);
        } else {
            this.history.push(record);
        }
    }
}