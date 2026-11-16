package com.fertitrack.app.asistente

/**
 * Guía agronómica de la lechuga (Lactuca sativa) que se añade al [PromptSistema]. Así Daniela
 * responde sobre el cultivo aunque la búsqueda de Google no esté disponible (plan gratuito).
 * Las cifras son orientativas y de fuentes de extensión agrícola; varían con la variedad,
 * la época y el suelo, y así se le pide que lo diga.
 */
object ConocimientoLechuga {

    val TEXTO: String = """
Guía de cultivo de lechuga (el cultivo principal del invernadero). Úsala para responder sobre lechuga sin buscar en internet. Las cifras son orientativas: dilo cuando des una, y recomienda un análisis de suelo o al técnico agrícola para decisiones importantes.

Tipos:
- Romana o cos: hojas alargadas y erguidas, aguanta algo más el calor.
- Batavia: hojas crujientes y onduladas, resistente al espigado.
- Mantecosa o trocadero: hojas suaves y tiernas, cabeza poco apretada, sensible al calor.
- Iceberg o arrepollada: cabeza compacta y crujiente, la más exigente en frío y en agua constante.
- Hoja suelta (lollo rosso, lollo bionda, hoja de roble): no forma cabeza, se cosecha antes y permite cortar hojas.
- Cogollo o little gem: romana pequeña, ciclo corto.

Clima:
- Cultivo de clima fresco. Crece mejor entre 15 y 20 °C de día y entre 7 y 12 °C de noche.
- Por encima de 25 a 28 °C se espiga (sube a flor), amarga y aparece el quemado de bordes. Los días largos y calurosos aceleran el espigado.
- Las plantas jóvenes aguantan heladas ligeras; las cabezas formadas se dañan con helada.
- La semilla germina en 3 a 7 días entre 15 y 20 °C. Por encima de unos 25 a 28 °C muchas variedades no germinan (termodormancia): en calor, sembrar en lugar fresco o al atardecer.
- Humedad relativa adecuada entre 60 y 80 %. Con más humedad y poca ventilación aumentan el mildiu y la botritis.
- En el invernadero: ventilar cuando pase de unos 25 °C y usar malla de sombreo (30 a 50 %) en las épocas de mucho sol.

Suelo:
- Suelto, franco o franco-arenoso, con buen drenaje y rico en materia orgánica.
- pH ideal entre 6,0 y 7,0. En suelos ácidos conviene encalar según el análisis.
- Es sensible a la salinidad: con agua o suelo salino crece menos y se queman los bordes de las hojas.
- Raíz superficial: casi todas las raíces están en los primeros 20 a 30 cm. Por eso la humedad que importa es la de la capa superficial, la que mide el sensor del robot.

Riego (lo más importante para la lechuga):
- Necesita humedad constante y uniforme: ni encharcar ni dejar secar.
- La falta de agua da hojas lacias, amargor, cabezas pequeñas, espigado y quemado de bordes.
- El exceso de agua pudre el cuello y las raíces (Pythium, Sclerotinia) y favorece hongos.
- Mejor riegos frecuentes y ligeros que pocos y abundantes. El goteo es lo ideal; la aspersión moja las hojas y favorece enfermedades.
- Regar por la mañana, para que las hojas y la superficie del suelo se sequen durante el día.
- La etapa más crítica va desde que empieza a formar la cabeza (o a crecer rápido) hasta la cosecha.
- Con FertiTrack: el robot solo riega las zonas que mide como secas. Para lechuga lo ideal es que las zonas estén casi siempre en intermedia o húmeda. Si muchas zonas salen sobre-húmedas, conviene alargar el descanso entre ciclos automáticos. Si salen secas a menudo o las plantas se ven lacias antes del riego, conviene acortar el descanso o subir el valor "SECA si humedad < (%)" (35 % de fábrica) en la calibración del sensor, desde la página web del robot: así riega antes. Bajarlo hace que riegue menos.

Fertilización:
- Abonado de fondo con materia orgánica bien descompuesta (compost maduro), antes de plantar.
- Necesidades orientativas por hectárea y ciclo: nitrógeno de 80 a 120 kg, fósforo (P2O5) de 30 a 60 kg y potasio (K2O) de 100 a 180 kg. Ajustar siempre con el análisis de suelo.
- Repartir el nitrógeno en 2 o 3 aplicaciones durante el crecimiento, no todo al principio.
- No abusar del nitrógeno: da hojas blandas, más plagas y enfermedades, y acumula nitratos en las hojas. Hacer la última aplicación de nitrógeno unas 2 o 3 semanas antes de cosechar.
- El potasio mejora el peso, el color y la conservación. El calcio y el magnesio previenen el quemado de bordes y el amarilleo.
- Fertilizar con el suelo húmedo, nunca con el suelo seco, porque se queman las raíces. Por eso FertiTrack sugiere fertilizar justo después de un riego.
- En hidroponía, como referencia: conductividad de la solución entre 1,2 y 1,8 mS/cm y pH entre 5,5 y 6,5.

Siembra y plantación:
- Semillero de 3 a 5 semanas; trasplantar con 4 a 6 hojas verdaderas.
- Plantar a poca profundidad, con el cepellón a ras del suelo: enterrar el cuello provoca pudriciones.
- Distancias orientativas: de 25 a 35 cm entre plantas y de 30 a 40 cm entre líneas (hoja suelta, 20 a 25 cm). Unas 8 a 12 plantas por metro cuadrado.
- Ciclo desde el trasplante hasta la cosecha: unos 30 a 45 días la hoja suelta y de 50 a 75 días la romana y la iceberg, según la época.
- Para cosechar todo el año, hacer plantaciones escalonadas cada 2 o 3 semanas.
- Rotación: no repetir lechuga ni otras compuestas (escarola, achicoria, girasol) en el mismo lugar en 2 o 3 años. Va bien después de leguminosas. Se asocia bien con rábano, zanahoria, cebolla y fresa.

Plagas frecuentes:
- Pulgones: colonias en el cogollo y el envés, hojas deformadas; transmiten virus.
- Trips: punteado plateado en las hojas; transmiten el virus del bronceado del tomate (TSWV).
- Mosca blanca y minadores (galerías blancas dentro de la hoja).
- Orugas (gusano soldado, plusia, heliotis): agujeros grandes en las hojas.
- Gusano gris y gusano de alambre: cortan o roen las plantas jóvenes por el cuello o la raíz.
- Babosas y caracoles: comen las hojas de noche y dejan rastro brillante.
- Nematodos de las agallas: nudos en las raíces, plantas pequeñas y amarillas.

Enfermedades frecuentes:
- Mildiu (Bremia lactucae): manchas amarillas angulosas en el haz y pelusa blanca en el envés. Aparece con frío, humedad alta y hojas mojadas. Prevención: ventilar, no mojar las hojas, variedades resistentes.
- Botritis o moho gris: podredumbre con moho gris, sobre todo en las hojas bajas y heridas.
- Esclerotinia: podredumbre blanda del cuello con moho blanco y pequeños granos negros; la planta se desploma. Se favorece con exceso de riego.
- Pudrición del cuello y caída de plántulas (Pythium, Rhizoctonia): plántulas que se doblan y mueren, sobre todo con encharcamiento.
- Oídio: polvillo blanco en las hojas, con tiempo más seco.
- Virus (mosaico de la lechuga, bronceado TSWV, venas grandes): mosaicos, deformaciones y plantas raquíticas. No tienen cura: controlar pulgones y trips, usar semilla certificada y arrancar las plantas enfermas.

Problemas fisiológicos (no son plagas):
- Quemado de bordes (tipburn): bordes de las hojas internas marrones o negros. Es falta de calcio en la hoja, por calor, crecimiento muy rápido, riego irregular o humedad alta. Se previene con riego constante, ventilación y sin exceso de nitrógeno.
- Espigado: la planta se alarga y florece, y las hojas amargan. Por calor, días largos o estrés de agua. Elegir variedades resistentes en verano y cosechar a tiempo.
- Amargor: por calor, falta de agua o cosecha tardía.

Manejo integrado:
- Mallas antiinsectos en las aberturas del invernadero.
- Trampas cromáticas: amarillas para pulgón, mosca blanca y minador; azules para trips.
- Control biológico: Orius contra trips, Aphidius y crisopas contra pulgones.
- Quitar las malas hierbas y los restos de cultivo, que hospedan plagas y virus.
- Si se usa un plaguicida, solo productos autorizados para lechuga en el país, siguiendo la etiqueta y respetando el plazo de seguridad antes de cosechar: la lechuga se come cruda. Para dosis o productos concretos, recomendar al técnico agrícola o la etiqueta del producto.

Cosecha y poscosecha:
- Cosechar cuando la cabeza está firme (iceberg, romana) o las hojas tienen el tamaño de venta (hoja suelta). No dejarla pasar: se espiga y amarga.
- Cosechar temprano en la mañana, con la planta fresca y turgente.
- Enfriar enseguida y guardar entre 0 y 2 °C con humedad del 95 % o más: así dura de 2 a 3 semanas.
- No guardarla junto a manzanas, tomates o plátanos: el etileno que sueltan le produce manchas marrones.

Inocuidad (se come cruda):
- Regar con agua limpia.
- Usar compost maduro y nunca estiércol fresco cerca de la cosecha, por el riesgo de bacterias como E. coli o Salmonella.
- Lavarse las manos y usar cajas y herramientas limpias al cosechar.
""".trim()
}
